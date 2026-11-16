package com.example.devicecontrol.ui

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size as AndroidSize
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FlashlightOff
import androidx.compose.material.icons.outlined.FlashlightOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.qrcode.QRCodeReader
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Full-screen offline CameraX + ZXing QR scanner (with LCD dot-matrix preprocessing),
 * tap-to-focus, torch control, and a manual link-input fallback dialog.
 */
@Composable
fun QrScannerDialog(
    onDismiss: () -> Unit,
    onCodeDetected: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val hasCameraPermission = remember(context) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    var camera by remember { mutableStateOf<Camera?>(null) }
    var torchEnabled by remember { mutableStateOf(false) }
    var cameraError by remember {
        mutableStateOf(if (hasCameraPermission) null else "未授予相机权限，请使用手动添加链接")
    }
    var showManualInput by remember { mutableStateOf(false) }
    var manualText by remember { mutableStateOf("") }

    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    LaunchedEffect(camera, torchEnabled) {
        if (camera?.cameraInfo?.hasFlashUnit() == true) {
            camera?.cameraControl?.enableTorch(torchEnabled)
        }
    }

    DisposableEffect(lifecycleOwner, previewView, hasCameraPermission) {
        if (!hasCameraPermission) {
            return@DisposableEffect onDispose {}
        }
        val analysisExecutor = Executors.newSingleThreadExecutor()
        val hasDelivered = AtomicBoolean(false)
        val isDisposed = AtomicBoolean(false)
        val reader = QRCodeReader()
        var boundProvider: ProcessCameraProvider? = null
        val providerFuture = ProcessCameraProvider.getInstance(context)

        providerFuture.addListener(
            {
                if (isDisposed.get()) return@addListener
                runCatching {
                    val provider = providerFuture.get()
                    boundProvider = provider

                    val preview = Preview.Builder().build().apply {
                        surfaceProvider = previewView.surfaceProvider
                    }

                    val resolutionSelector = ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                AndroidSize(1280, 720),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                            ),
                        )
                        .build()

                    val analysis = ImageAnalysis.Builder()
                        .setResolutionSelector(resolutionSelector)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                        try {
                            if (!isDisposed.get() && !hasDelivered.get()) {
                                val decoded = QrCodeDecoder.decodeFromImageProxy(imageProxy, reader)
                                if (!decoded.isNullOrBlank() &&
                                    !isDisposed.get() &&
                                    hasDelivered.compareAndSet(false, true)
                                ) {
                                    mainExecutor.execute { onCodeDetected(decoded) }
                                }
                            }
                        } finally {
                            imageProxy.close()
                        }
                    }

                    provider.unbindAll()
                    val boundCamera = provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                    camera = boundCamera
                    previewView.post {
                        triggerFocus(boundCamera, previewView, previewView.width / 2f, previewView.height / 2f)
                    }
                }.onFailure { err ->
                    cameraError = err.message ?: "相机初始化失败"
                }
            },
            mainExecutor,
        )

        onDispose {
            isDisposed.set(true)
            runCatching { boundProvider?.unbindAll() }
            analysisExecutor.shutdown()
        }
    }

    if (showManualInput) {
        AlertDialog(
            onDismissRequest = { showManualInput = false },
            title = { Text("手动添加链接") },
            text = {
                OutlinedTextField(
                    value = manualText,
                    onValueChange = { manualText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("二维码链接或 SN") },
                    placeholder = { Text("https://h5.qiekj.com/skip?SN=...") },
                    maxLines = 4,
                    shape = RoundedCornerShape(16.dp),
                )
            },
            dismissButton = {
                TextButton(onClick = { showManualInput = false }) {
                    Text("取消")
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val text = manualText.trim()
                        if (text.isNotEmpty()) {
                            showManualInput = false
                            onCodeDetected(text)
                        }
                    },
                    enabled = manualText.isNotBlank(),
                ) {
                    Text("添加")
                }
            },
            shape = RoundedCornerShape(24.dp),
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(camera) {
                    detectTapGestures { offset ->
                        camera?.let { triggerFocus(it, previewView, offset.x, offset.y) }
                    }
                },
        ) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize(),
            )

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
            ) {
                drawRect(Color.Black.copy(alpha = 0.52f))
                val boxSize = minOf(size.width, size.height) * 0.66f
                val left = (size.width - boxSize) / 2f
                val top = (size.height - boxSize) / 2f
                val corner = 20.dp.toPx()
                drawRoundRect(
                    color = Color.Transparent,
                    topLeft = Offset(left, top),
                    size = Size(boxSize, boxSize),
                    cornerRadius = CornerRadius(corner, corner),
                    blendMode = BlendMode.Clear,
                )
                drawRoundRect(
                    color = Color.White,
                    topLeft = Offset(left, top),
                    size = Size(boxSize, boxSize),
                    cornerRadius = CornerRadius(corner, corner),
                    style = Stroke(width = 2.5.dp.toPx()),
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 28.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "扫描设备二维码",
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (camera?.cameraInfo?.hasFlashUnit() == true) {
                            IconButton(onClick = { torchEnabled = !torchEnabled }) {
                                Icon(
                                    imageVector = if (torchEnabled) Icons.Outlined.FlashlightOn else Icons.Outlined.FlashlightOff,
                                    contentDescription = "手电筒",
                                    tint = Color.White,
                                )
                            }
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = "关闭扫码",
                                tint = Color.White,
                            )
                        }
                    }
                }

                Spacer(Modifier.weight(1f))

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (cameraError != null) {
                        Text(
                            text = cameraError!!,
                            color = Color.White.copy(alpha = 0.9f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(14.dp))
                    }
                    FilledTonalButton(
                        onClick = { showManualInput = true },
                        shape = RoundedCornerShape(20.dp),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = Color.White.copy(alpha = 0.2f),
                            contentColor = Color.White,
                        ),
                    ) {
                        Text("手动添加链接", maxLines = 1)
                    }
                    Spacer(Modifier.height(56.dp))
                }
            }
        }
    }
}

private fun triggerFocus(
    camera: Camera,
    previewView: PreviewView,
    x: Float,
    y: Float,
) {
    if (previewView.width <= 0 || previewView.height <= 0) return
    val point = previewView.meteringPointFactory.createPoint(x, y)
    val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
        .setAutoCancelDuration(3, TimeUnit.SECONDS)
        .build()
    runCatching { camera.cameraControl.startFocusAndMetering(action) }
}
