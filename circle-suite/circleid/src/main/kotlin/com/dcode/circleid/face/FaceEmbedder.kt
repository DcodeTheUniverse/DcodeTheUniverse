package com.dcode.circleid.face

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import com.dcode.circle.core.Embeddings
import com.dcode.circle.core.FaceAlignment
import com.dcode.circle.core.Pt
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * MobileFaceNet (Apache-2.0, Qualcomm AI Hub export): aligned 112×112 RGB face → 128-d identity embedding.
 * The model takes two images per call; we feed the face and its mirror image and fuse both embeddings,
 * which measurably stabilises scores across small pose changes. Runs fully on-device on the CPU (XNNPACK).
 */
class FaceEmbedder(context: Context) : AutoCloseable {
    private val interpreter: Interpreter
    private val size = FaceAlignment.SIZE
    private val inputA = ByteBuffer.allocateDirect(4 * 3 * size * size).order(ByteOrder.nativeOrder())
    private val inputB = ByteBuffer.allocateDirect(4 * 3 * size * size).order(ByteOrder.nativeOrder())
    private val output = Array(2) { FloatArray(128) }
    private val pixels = IntArray(size * size)
    private val aligned = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(aligned)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    init {
        val model = load(context, "mobile_facenet.tflite")
        interpreter = Interpreter(model, Interpreter.Options().setNumThreads(2))
    }

    private fun load(context: Context, name: String): MappedByteBuffer {
        val fd = context.assets.openFd(name)
        FileInputStream(fd.fileDescriptor).use { s ->
            return s.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }
    }

    /** Aligns the face with five landmarks (image coordinates of [upright]) and returns its L2-normalised embedding. */
    @Synchronized
    fun embed(upright: Bitmap, landmarks: List<Pt>): FloatArray? {
        val m = FaceAlignment.similarity(landmarks, FaceAlignment.TEMPLATE_NOSE_BASE)
        if (FaceAlignment.residual(landmarks, m, FaceAlignment.TEMPLATE_NOSE_BASE) > 9f) return null
        val matrix = Matrix().apply { setValues(m) }
        canvas.drawColor(android.graphics.Color.BLACK)
        canvas.drawBitmap(upright, matrix, paint)
        aligned.getPixels(pixels, 0, size, 0, 0, size, size)
        fill(inputA, mirror = false)
        fill(inputB, mirror = true)
        interpreter.runForMultipleInputsOutputs(arrayOf<Any>(inputA, inputB), mapOf<Int, Any>(0 to output))
        return Embeddings.fuseFlip(output[0], output[1])
    }

    /** NCHW float32 in [0, 1], RGB order (verified against LFW: RGB separates genuine/impostor better than BGR). */
    private fun fill(buf: ByteBuffer, mirror: Boolean) {
        buf.rewind()
        val plane = size * size
        val fb = buf.asFloatBuffer()
        for (c in 0 until 3) {
            val shift = when (c) { 0 -> 16; 1 -> 8; else -> 0 }
            for (y in 0 until size) {
                for (x in 0 until size) {
                    val sx = if (mirror) size - 1 - x else x
                    fb.put(c * plane + y * size + x, ((pixels[y * size + sx] shr shift) and 0xff) / 255f)
                }
            }
        }
        buf.rewind()
    }

    override fun close() {
        interpreter.close()
    }
}
