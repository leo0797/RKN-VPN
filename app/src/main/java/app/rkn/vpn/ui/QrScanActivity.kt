package app.rkn.vpn.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import app.rkn.vpn.R
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class QrScanActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            QrScanScreen(activity = this@QrScanActivity)
        }
    }

    companion object {
        const val EXTRA_SCAN_RESULT = "app.rkn.vpn.extra.SCAN_RESULT"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QrScanScreen(activity: ComponentActivity) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    var cameraError by remember { mutableStateOf(false) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // Привязка камеры возможна только после разрешения и создания PreviewView.
    DisposableEffect(hasCameraPermission, previewView) {
        val view = previewView
        if (!hasCameraPermission || view == null) {
            onDispose { }
        } else {
            val executor = Executors.newSingleThreadExecutor()
            var provider: ProcessCameraProvider? = null
            var scanner: BarcodeScanner? = null
            val delivered = AtomicBoolean(false)

            try {
                provider = ProcessCameraProvider.getInstance(activity).get()
                provider?.unbindAll()

                val preview = Preview.Builder().build().apply {
                    setSurfaceProvider(view.surfaceProvider)
                }
                scanner = BarcodeScanning.getClient(
                    BarcodeScannerOptions.Builder()
                        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                        .build()
                )
                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                imageAnalysis.setAnalyzer(executor) { image ->
                    analyzeFrame(image, scanner, delivered, activity)
                }
                provider?.bindToLifecycle(
                    activity,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis
                )
            } catch (e: Exception) {
                cameraError = true
            }

            onDispose {
                provider?.unbindAll()
                scanner?.close()
                executor.shutdown()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                }.also { previewView = it }
            },
            modifier = Modifier.fillMaxSize()
        )

        if (hasCameraPermission && !cameraError) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(Color.Black.copy(alpha = 0.55f))
            }
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(260.dp)
                        .border(BorderStroke(2.dp, Color.White), RoundedCornerShape(16.dp))
                )
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = stringResource(R.string.qr_scan_hint),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        TopAppBar(
            title = { Text(stringResource(R.string.qr_scan_title)) },
            navigationIcon = {
                IconButton(onClick = { activity.finish() }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back)
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Black,
                navigationIconContentColor = Color.White,
                titleContentColor = Color.White
            ),
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()
        )

        if (!hasCameraPermission) {
            PermissionDeniedContent(
                onOpenSettings = {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    }
                    context.startActivity(intent)
                },
                onCancel = { activity.finish() },
                modifier = Modifier.align(Alignment.Center)
            )
        } else if (cameraError) {
            CameraErrorContent(
                onClose = { activity.finish() },
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

@Composable
private fun PermissionDeniedContent(
    onOpenSettings: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.camera_permission_denied),
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge
        )
        Button(onClick = onOpenSettings) {
            Text(stringResource(R.string.open_settings))
        }
        TextButton(onClick = onCancel) {
            Text(stringResource(R.string.cancel), color = Color.White)
        }
    }
}

@Composable
private fun CameraErrorContent(
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.camera_open_error),
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge
        )
        Button(onClick = onClose) {
            Text(stringResource(R.string.close))
        }
    }
}

@OptIn(ExperimentalGetImage::class)
private fun analyzeFrame(
    image: ImageProxy,
    scanner: BarcodeScanner?,
    delivered: AtomicBoolean,
    activity: ComponentActivity
) {
    if (scanner == null) {
        image.close()
        return
    }
    try {
        val mediaImage = image.image
        if (delivered.get() || mediaImage == null) {
            image.close()
            return
        }
        val inputImage = InputImage.fromMediaImage(mediaImage, image.imageInfo.rotationDegrees)
        scanner.process(inputImage)
            .addOnSuccessListener { barcodes ->
                if (delivered.get()) return@addOnSuccessListener
                val value = barcodes.firstNotNullOfOrNull { barcode ->
                    barcode.rawValue?.takeIf { it.isNotBlank() }
                }
                if (value != null && delivered.compareAndSet(false, true)) {
                    activity.runOnUiThread {
                        val result = Intent().putExtra(QrScanActivity.EXTRA_SCAN_RESULT, value)
                        activity.setResult(Activity.RESULT_OK, result)
                        activity.finish()
                    }
                }
            }
            .addOnFailureListener { }
            .addOnCompleteListener { image.close() }
    } catch (e: Exception) {
        image.close()
    }
}
