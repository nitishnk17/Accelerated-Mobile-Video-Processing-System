package com.example.csproject

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.delay
import java.io.File

// logcat tag — filter by "CSProject" in android studio
private const val TAG = "CSProject"

class MainActivity : ComponentActivity() {

    // body implemented in native-lib.cpp via JNI
    external fun nativeGetStatus(): String

    // converts a yuv_420_888 frame to rgba using bt.601 in native code (zero-copy via direct ByteBuffers)
    external fun nativeYuvToRgba(
        yBuffer: java.nio.ByteBuffer, uBuffer: java.nio.ByteBuffer, vBuffer: java.nio.ByteBuffer,
        width: Int, height: Int,
        yRowStride: Int, uvRowStride: Int, uvPixelStride: Int,
        rgbaOut: java.nio.ByteBuffer
    )

    companion object {
        init {
            System.loadLibrary("csproject") // loads libcsproject.so
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, nativeGetStatus()) // verify JNI bridge at startup
        setContent { CameraApp() }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraApp() {
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)

    if (cameraPermissionState.status.isGranted) {
        CameraScreen()
    } else {
        // show permission prompt until user grants camera access
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("Camera permission is required.")
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = { cameraPermissionState.launchPermissionRequest() }) {
                Text("Request Permission")
            }
        }
    }
}

@Composable
fun CameraScreen() {
    val context = LocalContext.current

    // dashboard state — each var triggers only its own text to recompose
    var currentFps          by remember { mutableStateOf(0.0) }
    var processingLatencyMs by remember { mutableStateOf(0L) }
    var conversionLatencyMs by remember { mutableStateOf(0L) }
    var endToEndLatencyMs   by remember { mutableStateOf(0L) }
    var cpuUsagePercent     by remember { mutableStateOf(0.0) }
    var frameIntervalMs     by remember { mutableStateOf(-1L) }

    // pre-allocate the rgba output buffer once using direct memory; zero-copy for JNI
    val rgbaBuffer = remember { java.nio.ByteBuffer.allocateDirect(1280 * 720 * 4) }

    // need a reference to the activity to call the jni method
    val activity = context as MainActivity

    // non-reactive refs used only for cleanup on dispose
    val cameraDeviceRef   = remember { arrayOfNulls<CameraDevice>(1) }
    val captureSessionRef = remember { arrayOfNulls<CameraCaptureSession>(1) }

    // background thread for all camera2 callbacks; keeps ui thread free
    val backgroundThread  = remember { HandlerThread("Camera2Background").also { it.start() } }
    val backgroundHandler = remember { Handler(backgroundThread.looper) }
    val mainHandler       = remember { Handler(Looper.getMainLooper()) }

    // receives raw yuv_420_888 frames at 1280×720; maxImages=2 prevents stalls
    val imageReader = remember {
        ImageReader.newInstance(1280, 720, ImageFormat.YUV_420_888, 2)
    }

    // rolling window of last 30 frame arrival timestamps for stable fps
    val frameTimestamps = remember { ArrayDeque<Long>() }

    // previous frame hardware timestamp (ns); longarray lets the lambda mutate it
    val lastHwTimestampNs = remember { longArrayOf(-1L) }

    // poll cpu usage every ~1 s from /proc/stat
    LaunchedEffect(Unit) {
        while (true) {
            cpuUsagePercent = measureCpuUsage()
            delay(500)
        }
    }

    DisposableEffect(Unit) {
        imageReader.setOnImageAvailableListener({ reader ->
            val frameArrivalTime = System.currentTimeMillis()
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener

            try {
                // rolling 30-frame fps average
                frameTimestamps.addLast(frameArrivalTime)
                if (frameTimestamps.size > 30) frameTimestamps.removeFirst()
                val fps = if (frameTimestamps.size >= 2) {
                    val spanMs = frameTimestamps.last() - frameTimestamps.first()
                    if (spanMs > 0) (frameTimestamps.size - 1) * 1000.0 / spanMs else 0.0
                } else 0.0

                val processingStart = System.currentTimeMillis()

                // inter-frame interval from hardware timestamps; ~33 ms at 30 fps
                val hwTimestampNs = image.timestamp
                val intervalMs = if (lastHwTimestampNs[0] > 0L)
                    (hwTimestampNs - lastHwTimestampNs[0]) / 1_000_000L else -1L
                lastHwTimestampNs[0] = hwTimestampNs

                Log.d(TAG, "frame: hw=${hwTimestampNs / 1_000_000}ms  interval=${intervalMs}ms")
                if (intervalMs > 40L) Log.w(TAG, "frame gap ${intervalMs}ms — possible dropped frame")

            
                // image.planes[].buffer already returns direct ByteBuffers (no copy needed)
                val yBuffer = image.planes[0].buffer
                val uBuffer = image.planes[1].buffer
                val vBuffer = image.planes[2].buffer

                val yRowStride    = image.planes[0].rowStride
                val uvRowStride   = image.planes[1].rowStride
                val uvPixelStride = image.planes[1].pixelStride

                val convStart = System.currentTimeMillis()
                activity.nativeYuvToRgba(
                    yBuffer, uBuffer, vBuffer,
                    1280, 720,
                    yRowStride, uvRowStride, uvPixelStride,
                    rgbaBuffer
                )
                val convLatency = System.currentTimeMillis() - convStart

                val procLatency = System.currentTimeMillis() - processingStart
                val e2eLatency  = System.currentTimeMillis() - frameArrivalTime

                // post all metrics together for a single atomic recomposition
                mainHandler.post {
                    currentFps          = fps
                    processingLatencyMs = procLatency
                    conversionLatencyMs = convLatency
                    endToEndLatencyMs   = e2eLatency
                    frameIntervalMs     = intervalMs
                }
            } finally {
                image.close() // must close every image or camera stalls
            }
        }, backgroundHandler)

        onDispose {
            captureSessionRef[0]?.close()
            cameraDeviceRef[0]?.close()
            imageReader.close()
            backgroundThread.quitSafely()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {

        // textureview for live camera preview (classic view embedded in compose)
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val textureView = TextureView(ctx)
                textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                        openCamera(ctx, st, imageReader, backgroundHandler,
                            onCameraOpened   = { cameraDeviceRef[0]   = it },
                            onSessionCreated = { captureSessionRef[0] = it })
                    }
                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                }
                textureView
            }
        )

        // semi-transparent hud pinned to top-left corner
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            // colour-coded mode label — grey=baseline, blue=simd, green=gpu, yellow=hybrid
            Text("MODE: BASELINE", color = Color.Gray, fontSize = 13.sp, fontWeight = FontWeight.Bold)

            Text("FPS:  ${String.format("%.1f", currentFps)}", color = Color.White, fontSize = 13.sp)
            Text("Conv: ${conversionLatencyMs} ms",            color = Color.White, fontSize = 13.sp)
            Text("Proc: ${processingLatencyMs} ms",            color = Color.White, fontSize = 13.sp)
            Text("E2E:  ${endToEndLatencyMs} ms",              color = Color.White, fontSize = 13.sp)

            // turns yellow if interval > 40 ms (dropped frame at 30 fps)
            val intervalDisplay = if (frameIntervalMs < 0L) "--" else "${frameIntervalMs} ms"
            Text(
                text  = "Intv: $intervalDisplay",
                color = if (frameIntervalMs < 0L || frameIntervalMs in 28L..38L) Color.White else Color.Yellow,
                fontSize = 13.sp
            )

            Text("CPU:  ${String.format("%.1f", cpuUsagePercent)}%", color = Color.White, fontSize = 13.sp)
        }
    }
}

// reads /proc/stat twice 500 ms apart and returns cpu busy percentage
suspend fun measureCpuUsage(): Double {
    fun readStats(): LongArray = try {
        val line = File("/proc/stat").readLines().firstOrNull() ?: return LongArray(8)
        line.trim().split("\\s+".toRegex()).drop(1).take(8).map { it.toLong() }.toLongArray()
    } catch (e: Exception) { LongArray(8) }

    val s1 = readStats()
    delay(500)
    val s2 = readStats()

    val totalDelta = s2.sum() - s1.sum()
    val idleDelta  = (s2[3] + s2[4]) - (s1[3] + s1[4]) // index 3=idle, 4=iowait
    return if (totalDelta > 0) (totalDelta - idleDelta) * 100.0 / totalDelta else 0.0
}

// opens rear camera and starts a repeating capture session targeting 30 fps.
// frames are sent to both the preview surface and the imagereader callback.
@SuppressLint("MissingPermission")
fun openCamera(
    context: Context,
    surfaceTexture: SurfaceTexture,
    imageReader: ImageReader,
    backgroundHandler: Handler,
    onCameraOpened: (CameraDevice) -> Unit,
    onSessionCreated: (CameraCaptureSession) -> Unit
) {
    val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    // find the rear-facing camera id
    val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
        cameraManager.getCameraCharacteristics(id)
            .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
    } ?: run { Log.e(TAG, "no rear camera found"); return }

    // prefer [30,30] fps range to lock frame rate; fall back to highest available
    val characteristics    = cameraManager.getCameraCharacteristics(cameraId)
    val availableFpsRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
    val targetFpsRange     = availableFpsRanges?.firstOrNull { it.lower == 30 && it.upper == 30 }
                          ?: availableFpsRanges?.maxByOrNull { it.upper }
    Log.d(TAG, "fps ranges: ${availableFpsRanges?.toList()} → selected: $targetFpsRange")

    surfaceTexture.setDefaultBufferSize(1280, 720)
    val previewSurface = Surface(surfaceTexture)

    cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {

        override fun onOpened(camera: CameraDevice) {
            onCameraOpened(camera)
            val outputs = listOf(previewSurface, imageReader.surface)

            camera.createCaptureSession(outputs, object : CameraCaptureSession.StateCallback() {

                override fun onConfigured(session: CameraCaptureSession) {
                    onSessionCreated(session)
                    val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(previewSurface)
                        addTarget(imageReader.surface)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                        targetFpsRange?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                    }
                    session.setRepeatingRequest(req.build(), null, backgroundHandler)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "capture session configuration failed")
                }
            }, backgroundHandler)
        }

        override fun onDisconnected(camera: CameraDevice) { camera.close() }

        override fun onError(camera: CameraDevice, errorCode: Int) {
            Log.e(TAG, "camera error: $errorCode")
            camera.close()
        }
    }, backgroundHandler)
}
