package pantry.app.scan

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import pantry.core.OcrLine
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Shows [content] once the camera permission is granted, asking for it first if needed. */
@Composable
fun CameraPermissionGate(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    if (granted) {
        content()
    } else {
        Column(modifier.padding(24.dp)) {
            Text("The camera is needed to scan barcodes and receipts. Images are processed on the phone and never uploaded.")
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }, modifier = Modifier.padding(top = 12.dp)) {
                Text("Allow camera")
            }
        }
    }
}

/** Live preview that reports each barcode ML Kit decodes. The caller is responsible for de-duplicating repeats. */
@OptIn(ExperimentalGetImage::class)
@Composable
fun BarcodeScannerView(modifier: Modifier = Modifier, onBarcode: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    val callback by rememberUpdatedState(onBarcode)

    DisposableEffect(lifecycleOwner) {
        val executor = Executors.newSingleThreadExecutor()
        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A,
                    Barcode.FORMAT_UPC_E, Barcode.FORMAT_CODE_128, Barcode.FORMAT_QR_CODE,
                )
                .build(),
        )
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor) { proxy: ImageProxy ->
                val media = proxy.image
                if (media == null) {
                    proxy.close()
                } else {
                    val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                    scanner.process(input)
                        .addOnSuccessListener { codes ->
                            codes.firstNotNullOfOrNull { it.rawValue }?.let { callback(it) }
                        }
                        .addOnCompleteListener { proxy.close() }
                }
            }
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            runCatching { providerFuture.get().unbindAll() }
            scanner.close()
            executor.shutdown()
        }
    }
    AndroidView(factory = { previewView }, modifier = modifier)
}

/** Preview with a capture button. Recognized text fragments are handed to [onResult]. */
@Composable
fun ReceiptCaptureView(modifier: Modifier = Modifier, onResult: (List<OcrLine>) -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build() }
    val result by rememberUpdatedState(onResult)
    val error by rememberUpdatedState(onError)
    var busy by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
        }, ContextCompat.getMainExecutor(context))
        onDispose { runCatching { providerFuture.get().unbindAll() } }
    }

    Box(modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        Button(
            enabled = !busy,
            onClick = {
                busy = true
                capture.takePicture(ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        val bitmap = image.toBitmap()
                        val rotation = image.imageInfo.rotationDegrees
                        image.close()
                        recognize(InputImage.fromBitmap(bitmap, rotation)) { r ->
                            busy = false
                            r.fold({ result(it) }, { error(it.message ?: "Text recognition failed") })
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        busy = false
                        error(exception.message ?: "Capture failed")
                    }
                })
            },
            modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
        ) { Text(if (busy) "Reading..." else "Capture receipt") }
    }
}

/** Reads text from a photo or screenshot the user picked. */
suspend fun recognizeFromUri(context: Context, uri: Uri): List<OcrLine> =
    suspendCancellableCoroutine { cont ->
        try {
            recognize(InputImage.fromFilePath(context, uri)) { r ->
                r.fold({ cont.resume(it) }, { cont.resumeWithException(it) })
            }
        } catch (e: Exception) {
            cont.resumeWithException(e)
        }
    }

private fun recognize(image: InputImage, done: (Result<List<OcrLine>>) -> Unit) {
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    val task: Task<com.google.mlkit.vision.text.Text> = recognizer.process(image)
    task.addOnSuccessListener { text ->
        val lines = text.textBlocks.flatMap { it.lines }.mapNotNull { l ->
            l.boundingBox?.let { OcrLine(l.text, it.left, it.top, it.right, it.bottom) }
        }
        recognizer.close()
        done(Result.success(lines))
    }.addOnFailureListener { e ->
        recognizer.close()
        done(Result.failure(e))
    }
}
