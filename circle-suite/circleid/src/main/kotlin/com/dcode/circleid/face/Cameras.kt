package com.dcode.circleid.face

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dcode.circleid.ui.components.Caption
import com.dcode.circleid.ui.components.PillButton
import com.dcode.circleid.ui.theme.Circle
import java.util.concurrent.Executors

@Composable
fun CameraPermissionGate(rationale: String, content: @Composable () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }
    if (granted) {
        content()
    } else {
        Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Camera access", style = MaterialTheme.typography.headlineSmall, color = Circle.palette.ink, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Caption(rationale)
            Spacer(Modifier.height(24.dp))
            PillButton("Allow camera", onClick = { launcher.launch(Manifest.permission.CAMERA) })
        }
    }
}

/** Front camera preview + face analysis. Frames never leave the process and are never stored. */
@Composable
fun FaceCamera(analyzer: FaceAnalyzer, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    DisposableEffect(analyzer) {
        val executor = Executors.newSingleThreadExecutor()
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            val p = future.get()
            provider = p
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                        .build(),
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { it.setAnalyzer(executor, analyzer) }
            runCatching {
                p.unbindAll()
                p.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            provider?.unbindAll()
            executor.shutdown()
            analyzer.close()
        }
    }
    AndroidView({ previewView }, modifier)
}

/** Per-frame colour statistics from the rear camera with the torch on (finger over the lens). */
class PulseFrame(val timestampNs: Long, val r: Double, val g: Double, val b: Double, val patch: FloatArray)

@Composable
fun TorchCamera(onFrame: (PulseFrame) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    DisposableEffect(Unit) {
        val executor = Executors.newSingleThreadExecutor()
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var camera: Camera? = null
        future.addListener({
            val p = future.get()
            provider = p
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(ResolutionStrategy(Size(320, 240), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                        .build(),
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(executor) { image ->
                try {
                    onFrame(PulseStats.compute(image.planes[0].buffer, image.width, image.height, image.planes[0].rowStride, image.imageInfo.timestamp))
                } catch (_: Exception) {
                } finally {
                    image.close()
                }
            }
            runCatching {
                p.unbindAll()
                camera = p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
                camera?.cameraControl?.enableTorch(true)
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            runCatching { camera?.cameraControl?.enableTorch(false) }
            provider?.unbindAll()
            executor.shutdown()
        }
    }
    Spacer(modifier)
}

object PulseStats {
    const val PATCH = com.dcode.circle.core.VascularSignature.SIZE

    fun compute(buf: java.nio.ByteBuffer, w: Int, h: Int, rowStride: Int, ts: Long): PulseFrame {
        // Central square, sampled on a grid for the means and into a PATCH×PATCH red-channel map.
        val side = minOf(w, h) * 3 / 4
        val x0 = (w - side) / 2
        val y0 = (h - side) / 2
        var r = 0L; var g = 0L; var b = 0L; var n = 0
        val patch = FloatArray(PATCH * PATCH)
        val counts = IntArray(PATCH * PATCH)
        val step = 2
        var y = y0
        while (y < y0 + side) {
            var x = x0
            val row = y * rowStride
            while (x < x0 + side) {
                val i = row + x * 4
                val rr = buf.get(i).toInt() and 0xff
                val gg = buf.get(i + 1).toInt() and 0xff
                val bb = buf.get(i + 2).toInt() and 0xff
                r += rr; g += gg; b += bb; n++
                val px = (x - x0) * PATCH / side
                val py = (y - y0) * PATCH / side
                val pi = py * PATCH + px
                patch[pi] += rr.toFloat(); counts[pi]++
                x += step
            }
            y += step
        }
        for (i in patch.indices) if (counts[i] > 0) patch[i] /= counts[i]
        return PulseFrame(ts, r.toDouble() / n, g.toDouble() / n, b.toDouble() / n, patch)
    }
}
