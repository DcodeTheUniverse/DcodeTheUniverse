package com.dcode.circleid.face

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.dcode.circle.core.FaceAlignment
import com.dcode.circle.core.FaceObservation
import com.dcode.circle.core.Pt
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import kotlin.math.abs

/** One analysed camera frame. */
class FaceFrame(
    val observation: FaceObservation?,
    val embedding: FloatArray?,
    val yaw: Float,
    val pitch: Float,
)

/**
 * CameraX analyser: rotates the frame upright, runs ML Kit face detection (landmarks, eye/smile classification,
 * tracking), and computes an identity embedding for usable frames. Runs on CameraX's background executor;
 * ML Kit is awaited synchronously so frames never pile up.
 */
class FaceAnalyzer(
    private val embedder: FaceEmbedder?,
    private val yawSign: Float = 1f,
    private val pitchSign: Float = 1f,
    private val scorer: ((FloatArray) -> Float)? = null,
    private val onFrame: (FaceFrame) -> Unit,
) : ImageAnalysis.Analyzer {
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.15f)
            .enableTracking()
            .build(),
    )

    @Volatile
    var paused = false

    override fun analyze(image: ImageProxy) {
        try {
            if (paused) return
            val upright = upright(image)
            val faces = Tasks.await(detector.process(InputImage.fromBitmap(upright, 0)))
            val now = SystemClock.elapsedRealtime()
            if (faces.isEmpty()) {
                upright.recycle()
                onFrame(FaceFrame(null, null, 0f, 0f))
                return
            }
            val face = faces.maxBy { it.boundingBox.width() * it.boundingBox.height() }
            val yaw = face.headEulerAngleY * yawSign
            val pitch = face.headEulerAngleX * pitchSign
            val sizeRatio = face.boundingBox.width().toFloat() / upright.width
            val model = embedder
            val embedding = if (model != null && usable(face, sizeRatio)) {
                landmarks(face)?.let { runCatching { model.embed(upright, it) }.getOrNull() }
            } else {
                null
            }
            val observation = FaceObservation(
                trackingId = face.trackingId,
                timestampMs = now,
                faceCount = faces.size,
                leftEyeOpen = face.leftEyeOpenProbability,
                rightEyeOpen = face.rightEyeOpenProbability,
                smiling = face.smilingProbability,
                yaw = yaw,
                pitch = pitch,
                roll = face.headEulerAngleZ,
                sizeRatio = sizeRatio,
                matchScore = embedding?.let { e -> scorer?.invoke(e) },
            )
            upright.recycle()
            onFrame(FaceFrame(observation, embedding, yaw, pitch))
        } catch (_: Exception) {
            // A dropped frame is harmless; the next one arrives in ~33 ms.
        } finally {
            image.close()
        }
    }

    private fun upright(image: ImageProxy): Bitmap {
        val src = image.toBitmap()
        val rotation = image.imageInfo.rotationDegrees
        if (rotation == 0) return src
        val m = Matrix().apply { postRotate(rotation.toFloat()) }
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        src.recycle()
        return out
    }

    /** Only spend inference time on frames that can produce a trustworthy embedding. */
    private fun usable(face: Face, sizeRatio: Float): Boolean {
        val eyesOpen = (face.leftEyeOpenProbability ?: 1f) > 0.35f && (face.rightEyeOpenProbability ?: 1f) > 0.35f
        return sizeRatio > 0.22f && abs(face.headEulerAngleY) < 24 && abs(face.headEulerAngleX) < 22 && abs(face.headEulerAngleZ) < 25 && eyesOpen
    }

    private fun landmarks(face: Face): List<Pt>? {
        fun p(type: Int) = face.getLandmark(type)?.position?.let { Pt(it.x, it.y) }
        val le = p(FaceLandmark.LEFT_EYE) ?: return null
        val re = p(FaceLandmark.RIGHT_EYE) ?: return null
        val nose = p(FaceLandmark.NOSE_BASE) ?: return null
        val ml = p(FaceLandmark.MOUTH_LEFT) ?: return null
        val mr = p(FaceLandmark.MOUTH_RIGHT) ?: return null
        return FaceAlignment.order(le, re, nose, ml, mr)
    }

    fun close() {
        detector.close()
    }
}
