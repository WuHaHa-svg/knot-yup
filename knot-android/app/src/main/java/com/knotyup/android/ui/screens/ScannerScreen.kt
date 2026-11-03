package com.knotyup.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 扫码加群：CameraX 预览 + ZXing 解析二维码（knotyup 入群载荷）。 */
@Composable
internal fun ScannerScreen(
    t: (String) -> String,
    onResult: (addr: String, pin: String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    var hasPermission by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { hasPermission = it }

    DisposableEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(android.Manifest.permission.CAMERA)
        onDispose { }
    }
    // 返回键由导航处理（popBackStack = 关闭扫码）

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hasPermission) {
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { ctx ->
                    val previewView = androidx.camera.view.PreviewView(ctx)
                    val providerFuture = androidx.camera.lifecycle.ProcessCameraProvider.getInstance(ctx)
                    providerFuture.addListener({
                        val provider = providerFuture.get()
                        val preview = androidx.camera.core.Preview.Builder().build().apply {
                            setSurfaceProvider(previewView.surfaceProvider)
                        }
                        val analysis = androidx.camera.core.ImageAnalysis.Builder()
                            .setBackpressureStrategy(androidx.camera.core.ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                        analysis.setAnalyzer(ctx.mainExecutor) { image ->
                            decodeQr(image)?.let { payload ->
                                val (addr, pin) = parseJoinPayload(payload)
                                if (addr != null) {
                                    analysis.clearAnalyzer()
                                    onResult(addr, pin ?: "")
                                }
                            }
                            image.close()
                        }
                        runCatching {
                            provider.unbindAll()
                            provider.bindToLifecycle(lifecycleOwner, androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                        }
                    }, ctx.mainExecutor)
                    previewView
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // 取景框 + 提示
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .size(240.dp)
                    .border(2.dp, Color.White.copy(alpha = 0.9f), RoundedCornerShape(18.dp)),
            )
            Spacer(Modifier.height(20.dp))
            Text(t("scan_hint"), color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
        }

        // 关闭按钮
        Surface(
            onClick = onClose,
            shape = RoundedCornerShape(9.dp),
            color = Color.White.copy(alpha = 0.15f),
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).size(34.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("✕", color = Color.White, fontSize = 15.sp)
            }
        }
    }
}

/** YUV → ZXing 解码；命中返回二维码文本。 */
private fun decodeQr(image: androidx.camera.core.ImageProxy): String? {
    return runCatching {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
        val source = com.google.zxing.PlanarYUVLuminanceSource(
            bytes, plane.rowStride, image.height, 0, 0, image.width, image.height, false,
        )
        val bitmap = com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(source))
        com.google.zxing.MultiFormatReader().apply {
            setHints(mapOf(com.google.zxing.DecodeHintType.POSSIBLE_FORMATS to listOf(com.google.zxing.BarcodeFormat.QR_CODE)))
        }.decodeWithState(bitmap).text
    }.getOrNull()
}

/** knotyup 入群载荷解析：{"v":1,"app":"knotyup","addr":"ip:port","pin":"..."} */
private fun parseJoinPayload(text: String): Pair<String?, String?> {
    return runCatching {
        val o = org.json.JSONObject(text)
        if (o.optString("app") == "knotyup") {
            o.getString("addr") to o.optString("pin")
        } else null to null
    }.getOrDefault(null to null)
}
