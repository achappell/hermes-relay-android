package com.achappell.hermesrelay

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Decides what a scanned QR payload means. Only a valid `hermes-home://pair`
 * link is accepted; any other code is ignored so a stray QR code on the same
 * page cannot start a request to an unexpected host.
 */
internal object HomePairingScan {
    sealed interface Result {
        data class Accepted(val link: String) : Result

        data object NotPairingCode : Result
    }

    fun evaluate(payloads: List<String?>): Result? {
        if (payloads.isEmpty()) return null
        payloads.filterNotNull().forEach { raw ->
            if (HomePairingLink.parse(raw) is HomePairingInput.Parsed) {
                return Result.Accepted(raw.trim())
            }
        }
        return Result.NotPairingCode
    }
}

/**
 * Camera preview that scans for a Home pairing QR code. It asks for the camera
 * only when opened; if the camera is refused or missing it says so, and the
 * link and typed-code entry beside it keep working.
 */
@Composable
internal fun HomePairingScanner(
    onLink: (String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val hasCamera = remember {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    }
    var permitted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var denied by remember { mutableStateOf(false) }
    var sawOtherCode by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permitted = granted
        denied = !granted
    }
    LaunchedEffect(hasCamera) {
        if (hasCamera && !permitted) permission.launch(Manifest.permission.CAMERA)
    }

    Column(
        modifier = Modifier.testTag("android_home_pair_scanner"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val status = when {
            !hasCamera -> R.string.android_home_pair_scan_no_camera
            denied -> R.string.android_home_pair_scan_denied
            sawOtherCode -> R.string.android_home_pair_scan_other_code
            else -> R.string.android_home_pair_scan_hint
        }
        if (hasCamera && permitted) {
            CameraScanView(
                onLink = onLink,
                onOtherCode = { sawOtherCode = true },
            )
        }
        Text(
            modifier = Modifier
                .testTag("android_home_pair_scan_status")
                .semantics { liveRegion = LiveRegionMode.Polite },
            text = stringResource(status),
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(
            modifier = Modifier.testTag("android_home_pair_scan_close"),
            onClick = onClose,
        ) {
            Text(stringResource(R.string.android_home_pair_scan_close))
        }
    }
}

@Composable
private fun CameraScanView(
    onLink: (String) -> Unit,
    onOtherCode: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnLink by rememberUpdatedState(onLink)
    val latestOnOtherCode by rememberUpdatedState(onOtherCode)
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    DisposableEffect(lifecycleOwner) {
        val analysisExecutor = Executors.newSingleThreadExecutor()
        val reader = HomePairingQrDecoder.reader()
        val delivered = AtomicBoolean(false)
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null

        fun analyze(image: ImageProxy) {
            val payload = try {
                if (delivered.get()) null else decodeQr(reader, image)
            } finally {
                image.close()
            }
            val result = HomePairingScan.evaluate(listOfNotNull(payload)) ?: return
            mainExecutor.execute {
                when (result) {
                    is HomePairingScan.Result.Accepted ->
                        if (delivered.compareAndSet(false, true)) latestOnLink(result.link)
                    HomePairingScan.Result.NotPairingCode -> latestOnOtherCode()
                }
            }
        }

        providerFuture.addListener(
            {
                val cameraProvider = runCatching { providerFuture.get() }.getOrNull()
                    ?: return@addListener
                provider = cameraProvider
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(analysisExecutor, ::analyze) }
                val selector = if (cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                    CameraSelector.DEFAULT_BACK_CAMERA
                } else {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                }
                runCatching {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
                }
            },
            mainExecutor,
        )

        onDispose {
            delivered.set(true)
            provider?.unbindAll()
            analysisExecutor.shutdown()
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            // The preview scales to fill; without clipping it paints over
            // the text above and below it.
            .clipToBounds()
            .testTag("android_home_pair_scan_preview"),
    )
}

/** Decodes a QR code from a camera frame's luminance (Y) plane, or returns null. */
private fun decodeQr(reader: MultiFormatReader, image: ImageProxy): String? {
    val plane = image.planes.firstOrNull() ?: return null
    return HomePairingQrDecoder.decode(
        reader,
        HomePairingQrDecoder.visibleLuminance(
            plane.buffer,
            plane.rowStride,
            image.width,
            image.height,
        ),
        image.width,
        image.height,
    )
}

/** Camera-free QR decoding, so the scanner's decode path is unit-testable. */
internal object HomePairingQrDecoder {
    fun reader(): MultiFormatReader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true,
            ),
        )
    }

    /** Copies only visible pixels; row padding would otherwise shear the image. */
    fun visibleLuminance(
        plane: java.nio.ByteBuffer,
        rowStride: Int,
        width: Int,
        height: Int,
    ): ByteArray {
        val buffer = plane.duplicate().apply { rewind() }
        val luminance = ByteArray(width * height)
        for (row in 0 until height) {
            buffer.position(row * rowStride)
            buffer.get(luminance, row * width, width)
        }
        return luminance
    }

    /** QR finder patterns are rotation-invariant, so frames decode as delivered. */
    fun decode(reader: MultiFormatReader, luminance: ByteArray, width: Int, height: Int): String? {
        val source = PlanarYUVLuminanceSource(luminance, width, height, 0, 0, width, height, false)
        return try {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
        } catch (_: NotFoundException) {
            null
        } catch (_: RuntimeException) {
            null
        } finally {
            reader.reset()
        }
    }
}
