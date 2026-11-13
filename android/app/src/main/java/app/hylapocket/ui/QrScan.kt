package app.hylapocket.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraInfo
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.CameraFilter
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import app.hylapocket.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.android.gms.tasks.Tasks
import androidx.camera.camera2.interop.Camera2CameraInfo
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors

/**
 * Looks for the code on the hylad machine: through the camera, or in a picture of it. The camera
 * is asked for only once the screen is up, so that the dialog is not the permission prompt's
 * second question.
 */
@Composable
internal fun ScanCodeDialog(done: CompletableDeferred<Unit>, onDismiss: () -> Unit, onText: (String) -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var noCode by remember { mutableStateOf(false) }
    /** Why there is no camera: the permission was refused, or the phone has none this app can open. */
    var cameraProblem by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                val text = withContext(Dispatchers.IO) {
                    runCatching {
                        val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                        bitmap?.let(::scanImage) ?: bitmap?.let(::mlkitImage)
                    }.getOrNull()
                }
                if (done.isCompleted) return@launch
                if (text != null) onText(text) else noCode = true
            }
        }
    }
    LaunchedEffect(Unit) {
        if (!granted) permission.launch(Manifest.permission.CAMERA)
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Colors.surface,
        title = { Text(stringResource(R.string.scan_title), color = Colors.ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                // The camera, or why there is none: the picture is the way then.
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).size(width = 280.dp, height = 280.dp)
                        .background(Color.Black),
                ) {
                    val problem = cameraProblem ?: if (granted) null else stringResource(R.string.scan_needs_camera)
                    if (problem == null) {
                        CameraPreview(done, onText) { why ->
                            // A second frame later, as the camera retries, is the same answer.
                            if (cameraProblem == null) cameraProblem = why
                        }
                    } else {
                        Text(
                            problem,
                            color = Colors.muted,
                            fontSize = TextStyles.label,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.align(Alignment.Center).padding(16.dp),
                        )
                    }
                }
                if (noCode) {
                    Text(stringResource(R.string.scan_none), color = Colors.danger, fontSize = TextStyles.label)
                }
                androidx.compose.material3.TextButton(
                    onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                ) {
                    Text(stringResource(R.string.scan_pick), color = Colors.accent, fontSize = TextStyles.label)
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel), color = Colors.muted)
            }
        },
    )
}

/**
 * The camera's frames, analysed for a QR code. ZXing reads the luminance plane on the analysing
 * thread, which costs nothing on the main thread, and MlKit reads frames ZXing could not: a code
 * seen at an angle, or in a picture with little contrast.
 */
@Composable
private fun CameraPreview(done: CompletableDeferred<Unit>, onText: (String) -> Unit, onProblem: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val analysis = remember { Executors.newSingleThreadExecutor() }
    val reader = remember { MultiFormatReader() }
    val mlkit = remember { BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()) }
    DisposableEffect(Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = try {
                future.get()
            } catch (e: Exception) {
                onProblem(noCamera(context))
                return@addListener
            }
            // By its id, not its selector: a selector is checked against the cameras CameraX
            // validates, which a phone whose camera fails that check still has.
            val camera = provider.availableCameraInfos
                .firstOrNull { info -> info.lensFacing == CameraSelector.LENS_FACING_BACK }
                ?: provider.availableCameraInfos.firstOrNull()
            if (camera == null) {
                onProblem(noCamera(context))
                return@addListener
            }
            val id = Camera2CameraInfo.from(camera).cameraId
            val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
            val image = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetResolution(Size(1280, 720))
                .build()
            image.setAnalyzer(analysis) { proxy ->
                // One code closes the dialog: a second frames later is a second call.
                if (done.isCompleted) {
                    proxy.close()
                    return@setAnalyzer
                }
                val text = zxing(reader, proxy) ?: mlkit(mlkit, proxy)
                proxy.close()
                if (text != null && done.complete(Unit)) onText(text)
            }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.Builder().requireLensFacing(camera.lensFacing).addCameraFilter { list -> list.filter { Camera2CameraInfo.from(it).cameraId == id } }.build(), preview, image)
            } catch (e: Exception) {
                onProblem(noCamera(context))
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
            analysis.shutdown()
            mlkit.close()
        }
    }
    AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
}

/**
 * Why the camera cannot be opened: refused, held by another app, or none the phone offers. The
 * three have different fixes, and a black square says none of them.
 */
private fun noCamera(context: android.content.Context): String =
    context.getString(
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            R.string.scan_no_camera
        } else {
            R.string.scan_needs_camera
        },
    )

/** The luminance of the frame, as ZXing wants it; the proxy is closed by the caller. */
private fun luminance(proxy: ImageProxy): PlanarYUVLuminanceSource {
    val plane = proxy.planes[0]
    val buffer = plane.buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    val data = if (plane.pixelStride == 1 && plane.rowStride == proxy.width) bytes else {
        // Row padding, which some sizes have: keep the first width bytes of each row.
        ByteArray(proxy.width * proxy.height) { i ->
            val row = i / proxy.width
            val col = i % proxy.width
            bytes.getOrElse(row * plane.rowStride + col * plane.pixelStride) { 0 }
        }
    }
    return PlanarYUVLuminanceSource(data, proxy.width, proxy.height, 0, 0, proxy.width, proxy.height, false)
}

/**
 * Up to [Limit] pixels a side: a QR code in a still picture may fill any part of it, so the whole
 * picture is read, and a phone picture is far more than ZXing will take whole.
 */
private const val Limit = 1200

/** The first QR code in the frame, or null. */
private fun zxing(reader: MultiFormatReader, proxy: ImageProxy): String? = runCatching {
    reader.decodeWithState(BinaryBitmap(HybridBinarizer(luminance(proxy)))).text
}.getOrNull()

/** A QR code in a still picture, as ZXing reads it; null when there is none. */
private fun scanImage(bitmap: android.graphics.Bitmap): String? {
    val scale = minOf(1f, Limit.toFloat() / maxOf(bitmap.width, bitmap.height))
    val scaled = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
    val w = scaled.width
    val h = scaled.height
    val pixels = IntArray(w * h)
    scaled.getPixels(pixels, 0, w, 0, 0, w, h)
    val luma = ByteArray(w * h) { i ->
        val p = pixels[i]
        val r = (p shr 16) and 0xff
        val g = (p shr 8) and 0xff
        val b = p and 0xff
        ((r * 66 + g * 129 + b * 25 + 128) shr 8).toByte()
    }
    val source = PlanarYUVLuminanceSource(luma, w, h, 0, 0, w, h, false)
    return runCatching { MultiFormatReader().decodeWithState(BinaryBitmap(HybridBinarizer(source))).text }.getOrNull()
}

/**
 * MlKit's reading of a still picture, which ZXing can miss at a slight angle. It has no answer,
 * rather than an error, when there is no code, and so is the caller's null.
 */
private fun mlkitImage(bitmap: android.graphics.Bitmap): String? {
    val client = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
    return try {
        Tasks.await(client.process(InputImage.fromBitmap(bitmap, 0)), 5, java.util.concurrent.TimeUnit.SECONDS)
            .mapNotNull { it.rawValue }.firstOrNull()
    } catch (_: Exception) {
        null
    } finally {
        client.close()
    }
}

/** MlKit's reading of the frame, which runs on its own thread; the caller waits for it. */
private fun mlkit(client: com.google.mlkit.vision.barcode.BarcodeScanner, proxy: ImageProxy): String? = runCatching {
    val image = InputImage.fromMediaImage(proxy.image!!, proxy.imageInfo.rotationDegrees)
    com.google.android.gms.tasks.Tasks.await(client.process(image), 1, java.util.concurrent.TimeUnit.SECONDS)
        .mapNotNull { it.rawValue }.firstOrNull()
}.getOrNull()
