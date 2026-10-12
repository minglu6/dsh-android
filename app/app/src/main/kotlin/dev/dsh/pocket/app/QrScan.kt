package dev.dsh.pocket.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import dev.dsh.pocket.protocol.PairingLink
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Starts the in-app CameraX + ML Kit QR scanner after ensuring CAMERA permission.
 * Denied permission surfaces a themed bottom sheet (not a system AlertDialog).
 */
@Composable
fun ScanPairFlow(onResult: (PairingLink) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var showDenied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) granted = true else showDenied = true
    }
    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(Manifest.permission.CAMERA)
    }
    when {
        granted -> QrScannerDialog(
            onResult = { onResult(it); onDismiss() },
            onDismiss = onDismiss,
        )
        showDenied -> CameraPermissionSheet(
            onDismiss = onDismiss,
            onOpenSettings = {
                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
                onDismiss()
            },
        )
    }
}

@Composable
fun CameraPermissionSheet(onDismiss: () -> Unit, onOpenSettings: () -> Unit) {
    PocketSheet(onDismiss) {
        SheetTitle(stringResource(R.string.camera_permission_title))
        Text(
            stringResource(R.string.camera_permission_body),
            fontSize = 14.sp, color = T.Text2, lineHeight = 22.sp,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
        )
        Row(Modifier.padding(horizontal = 22.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onDismiss, Modifier.weight(1f).height(50.dp), shape = T.R12,
                border = BorderStroke(1.dp, T.Border),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = T.Ink),
            ) { Text(stringResource(R.string.camera_permission_cancel)) }
            Button(
                onOpenSettings, Modifier.weight(1f).height(50.dp), shape = T.R12, colors = PrimaryColors(),
            ) { Text(stringResource(R.string.camera_permission_settings), color = Color.White) }
        }
    }
}

@Composable
private fun QrScannerDialog(onResult: (PairingLink) -> Unit, onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            // Pairing QR must remain screenshot-able; do not set FLAG_SECURE.
            securePolicy = SecureFlagPolicy.SecureOff,
        ),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            CameraPreview(onBarcode = { text ->
                PairingLink.parse(text)?.let(onResult)
            })
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onDismiss) { Icon(D.chevronLeft, "返回", tint = Color.White) }
                    Text(stringResource(R.string.qr_scan_title), fontSize = 18.sp, color = Color.White)
                }
                Spacer(Modifier.weight(1f))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(240.dp).border(2.dp, Color.White.copy(alpha = 0.85f), T.R16))
                }
                Text(
                    stringResource(R.string.qr_scan_hint),
                    color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp,
                    modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 48.dp),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CameraPreview(onBarcode: (String) -> Unit) {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val done = remember { AtomicBoolean(false) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val providerRef = remember { mutableStateOf<ProcessCameraProvider?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            providerRef.value?.unbindAll()
            executor.shutdown()
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { c ->
            PreviewView(c).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }
        },
        update = { previewView ->
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                val provider = providerFuture.get()
                providerRef.value = provider
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                val options = BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
                val scanner = BarcodeScanning.getClient(options)
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { proxy ->
                    if (done.get()) { proxy.close(); return@setAnalyzer }
                    val media = proxy.image
                    if (media == null) { proxy.close(); return@setAnalyzer }
                    val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                    scanner.process(image)
                        .addOnSuccessListener { codes ->
                            if (done.get()) return@addOnSuccessListener
                            val text = codes.firstOrNull()?.rawValue ?: return@addOnSuccessListener
                            if (PairingLink.parse(text) != null && done.compareAndSet(false, true)) {
                                provider.unbindAll()
                                onBarcode(text)
                            }
                        }
                        .addOnCompleteListener { proxy.close() }
                }
                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                }
            }, ContextCompat.getMainExecutor(ctx))
        },
    )
}
