package com.aiapp.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.aiapp.llm.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File

/**
 * Free, unlimited "story video": a free model writes a scene list from your prompt, free image
 * models draw each scene, and the phone turns them into an MP4 with slow cinematic camera moves,
 * crossfades and captions. No key, no limits, nothing leaves the phone except the image prompts.
 */
object StoryVideo {
    data class Scene(val image: String, val caption: String)

    private const val FPS = 24
    private const val SCENE_SECONDS = 4.0
    private const val FADE_SECONDS = 0.8

    /** Asks a free text model for [count] visual scenes; falls back to simple variations. */
    suspend fun plan(prompt: String, count: Int): List<Scene> {
        val ask = buildJsonObject {
            put("model", "openai")
            put("private", true)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    put(
                        "content",
                        "Write a $count-shot cinematic storyboard for this video idea: \"$prompt\".\n" +
                            "Reply with exactly $count lines, one per shot, each as: IMAGE PROMPT || SHORT CAPTION\n" +
                            "IMAGE PROMPT: a vivid, detailed, photorealistic cinematic still (subject, setting, lighting, lens, mood), " +
                            "consistent characters and style across shots. SHORT CAPTION: at most 8 words, or leave empty. No numbering, no extra text.",
                    )
                }
            }
        }.toString()
        val text = runCatching {
            val o = Http.postJson("https://text.pollinations.ai/openai", emptyMap(), ask)
            val choice = (o["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
            ((choice?.get("message") as? JsonObject)?.get("content") as? JsonPrimitive)?.contentOrNull.orEmpty()
        }.getOrDefault("")
        val scenes = text.lines().map { it.trim().trimStart('-', '*', ' ').replace(Regex("^\\d+[.)]\\s*"), "") }
            .filter { it.length > 15 }
            .map { line ->
                val parts = line.split("||")
                Scene(parts[0].trim(), parts.getOrNull(1)?.trim().orEmpty())
            }
            .take(count)
        if (scenes.size >= 2) return scenes
        val angles = listOf("wide establishing shot", "medium shot", "close-up", "dramatic low angle", "aerial view", "golden hour silhouette", "detail shot", "final wide shot")
        return (0 until count).map { i -> Scene("$prompt, ${angles[i % angles.size]}, cinematic film still, photorealistic", "") }
    }

    /**
     * Builds the whole video and returns the MP4 file. [onStatus] gets (progress 0..1, text).
     */
    suspend fun make(
        context: Context,
        prompt: String,
        count: Int,
        portrait: Boolean,
        captions: Boolean,
        imageModel: String,
        onStatus: (Float, String) -> Unit,
    ): File {
        onStatus(0.02f, "Writing the storyboard…")
        val scenes = plan(prompt, count)
        val (w, h) = if (portrait) 720 to 1280 else 1280 to 720
        val dir = File(context.cacheDir, "story").apply { deleteRecursively(); mkdirs() }

        // Draw each scene with a free image model (a little bigger than the frame, for camera moves).
        val images = mutableListOf<File>()
        scenes.forEachIndexed { i, s ->
            onStatus(0.05f + 0.6f * i / scenes.size, "Drawing scene ${i + 1} of ${scenes.size}…")
            val bytes = FreeImages.image(s.image, imageModel, if (portrait) 864 else 1536, if (portrait) 1536 else 864, null) {}
            val f = File(dir, "scene$i.jpg").apply { writeBytes(bytes) }
            images += f
        }

        onStatus(0.68f, "Animating and encoding the video…")
        val out = File(context.cacheDir, "story_${System.currentTimeMillis()}.mp4")
        encode(images, scenes.map { if (captions) it.caption else "" }, w, h, out) { p ->
            onStatus(0.68f + 0.3f * p, "Animating and encoding… ${(p * 100).toInt()}%")
        }
        dir.deleteRecursively()
        onStatus(1f, "Done")
        return out
    }

    /** H.264 encode with slow zoom/pan per scene, crossfades and captions. */
    private suspend fun encode(images: List<File>, captions: List<String>, w: Int, h: Int, out: File, onProgress: (Float) -> Unit) =
        withContext(Dispatchers.Default) {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, 5_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var track = -1
            var muxing = false
            val info = MediaCodec.BufferInfo()

            val frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(frame)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            val pixels = IntArray(w * h)

            val perScene = (SCENE_SECONDS * FPS).toInt()
            val fade = (FADE_SECONDS * FPS).toInt()
            val total = perScene * images.size
            var current: Bitmap? = null
            var next: Bitmap? = null
            var loadedIndex = -1

            fun load(i: Int): Bitmap? = images.getOrNull(i)?.let { BitmapFactory.decodeFile(it.absolutePath) }

            fun drain(endOfStream: Boolean) {
                while (true) {
                    val idx = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
                    when {
                        idx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                        idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            track = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxing = true
                        }
                        idx >= 0 -> {
                            val buf = codec.getOutputBuffer(idx)!!
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                            if (info.size > 0 && muxing) {
                                buf.position(info.offset)
                                buf.limit(info.offset + info.size)
                                muxer.writeSampleData(track, buf, info)
                            }
                            codec.releaseOutputBuffer(idx, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                        }
                    }
                }
            }

            try {
                for (f in 0 until total) {
                    ensureActive()
                    val scene = f / perScene
                    if (scene != loadedIndex) {
                        current?.recycle()
                        current = if (next != null && loadedIndex == scene - 1) next else load(scene)
                        next = null
                        loadedIndex = scene
                    }
                    val t = (f % perScene).toFloat() / perScene
                    canvas.drawColor(Color.BLACK)
                    current?.let { drawKenBurns(canvas, it, w, h, t, scene, paint, 255) }
                    // Crossfade into the next scene at the end of this one.
                    val toEnd = perScene - (f % perScene)
                    if (toEnd <= fade && scene + 1 < images.size) {
                        if (next == null) next = load(scene + 1)
                        val alpha = (255 * (1f - toEnd.toFloat() / fade)).toInt().coerceIn(0, 255)
                        next?.let { drawKenBurns(canvas, it, w, h, 0f, scene + 1, paint, alpha) }
                    }
                    val cap = captions.getOrNull(scene).orEmpty()
                    if (cap.isNotBlank()) drawCaption(canvas, cap, w, h, t)
                    // Fade in at the start and out at the end.
                    val edge = minOf(f, total - 1 - f)
                    if (edge < fade) canvas.drawColor(Color.argb(255 - 255 * edge / fade, 0, 0, 0))

                    frame.getPixels(pixels, 0, w, 0, 0, w, h)
                    var inIdx: Int
                    do {
                        inIdx = codec.dequeueInputBuffer(10_000)
                        if (inIdx < 0) drain(false)
                    } while (inIdx < 0)
                    val image = codec.getInputImage(inIdx)
                    if (image != null) {
                        writeYuv(pixels, w, h, image)
                    } else {
                        // Rare encoders without image access: plain I420 layout.
                        writeI420(pixels, w, h, codec.getInputBuffer(inIdx)!!)
                    }
                    codec.queueInputBuffer(inIdx, 0, w * h * 3 / 2, f * 1_000_000L / FPS, 0)
                    drain(false)
                    if (f % 12 == 0) onProgress(f.toFloat() / total)
                }
                val eos = codec.dequeueInputBuffer(100_000)
                if (eos >= 0) codec.queueInputBuffer(eos, 0, 0, total * 1_000_000L / FPS, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                drain(true)
            } finally {
                runCatching { codec.stop() }
                codec.release()
                if (muxing) runCatching { muxer.stop() }
                muxer.release()
                current?.recycle()
                next?.recycle()
                frame.recycle()
            }
            onProgress(1f)
        }

    /** Slow zoom and pan; direction alternates per scene so it feels like a moving camera. */
    private fun drawKenBurns(c: Canvas, b: Bitmap, w: Int, h: Int, t: Float, scene: Int, paint: Paint, alpha: Int) {
        val cover = maxOf(w.toFloat() / b.width, h.toFloat() / b.height)
        val ease = t * t * (3 - 2 * t)
        val zoomIn = scene % 2 == 0
        val scale = cover * (if (zoomIn) 1.0f + 0.12f * ease else 1.12f - 0.12f * ease)
        val dw = b.width * scale
        val dh = b.height * scale
        val panX = (dw - w) * (if (scene % 3 == 0) ease else if (scene % 3 == 1) 1 - ease else 0.5f)
        val panY = (dh - h) * 0.5f
        val m = Matrix().apply {
            postScale(scale, scale)
            postTranslate(-panX, -panY)
        }
        paint.alpha = alpha
        c.drawBitmap(b, m, paint)
        paint.alpha = 255
    }

    private fun drawCaption(c: Canvas, text: String, w: Int, h: Int, t: Float) {
        val alpha = when {
            t < 0.15f -> (t / 0.15f * 255).toInt()
            t > 0.85f -> ((1 - t) / 0.15f * 255).toInt()
            else -> 255
        }.coerceIn(0, 255)
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.alpha = alpha
            textSize = w * 0.045f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            setShadowLayer(8f, 0f, 2f, Color.argb(alpha, 0, 0, 0))
        }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, tp, (w * 0.86f).toInt())
            .setAlignment(Layout.Alignment.ALIGN_CENTER).build()
        val top = h * 0.82f - layout.height / 2f
        val bg = Paint().apply { color = Color.argb((alpha * 0.35f).toInt(), 0, 0, 0) }
        c.drawRect(RectF(0f, top - h * 0.02f, w.toFloat(), top + layout.height + h * 0.02f), bg)
        c.save()
        c.translate(w * 0.07f, top)
        layout.draw(c)
        c.restore()
    }

    /** ARGB → YUV 4:2:0 into the encoder's input image (works for any plane layout). */
    private fun writeYuv(argb: IntArray, w: Int, h: Int, image: android.media.Image) {
        val planes = image.planes
        val yBuf = planes[0].buffer
        val yRow = planes[0].rowStride
        val yPix = planes[0].pixelStride
        val uBuf = planes[1].buffer
        val vBuf = planes[2].buffer
        val uRow = planes[1].rowStride
        val uPix = planes[1].pixelStride
        val vRow = planes[2].rowStride
        val vPix = planes[2].pixelStride
        for (j in 0 until h) {
            for (i in 0 until w) {
                val c = argb[j * w + i]
                val r = (c shr 16) and 0xff
                val g = (c shr 8) and 0xff
                val b = c and 0xff
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yBuf.put(j * yRow + i * yPix, y.coerceIn(0, 255).toByte())
                if (j % 2 == 0 && i % 2 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    uBuf.put((j / 2) * uRow + (i / 2) * uPix, u.coerceIn(0, 255).toByte())
                    vBuf.put((j / 2) * vRow + (i / 2) * vPix, v.coerceIn(0, 255).toByte())
                }
            }
        }
    }

    private fun writeI420(argb: IntArray, w: Int, h: Int, out: java.nio.ByteBuffer) {
        val ySize = w * h
        val qSize = ySize / 4
        for (j in 0 until h) {
            for (i in 0 until w) {
                val c = argb[j * w + i]
                val r = (c shr 16) and 0xff
                val g = (c shr 8) and 0xff
                val b = c and 0xff
                out.put(j * w + i, ((((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(0, 255)).toByte())
                if (j % 2 == 0 && i % 2 == 0) {
                    val k = (j / 2) * (w / 2) + (i / 2)
                    out.put(ySize + k, ((((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255)).toByte())
                    out.put(ySize + qSize + k, ((((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255)).toByte())
                }
            }
        }
    }
}
