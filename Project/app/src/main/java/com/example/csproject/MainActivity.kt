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
import androidx.compose.ui.viewinterop.AndroidView
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import android.graphics.Bitmap
import android.graphics.Matrix
import java.nio.ByteBuffer
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.delay
import java.io.File
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Divider

// logcat tag — filter by "CSProject" in android studio
private const val TAG = "CSProject"

class MainActivity : ComponentActivity() {

    // body implemented in native-lib.cpp via JNI
    external fun nativeGetStatus(): String

    // converts yuv_420_888 planes to rgba using bt.601 in native code
    // stores result in a new bytearray and returns it to kotlin
    external fun nativeYuvToRgba(
        yBytes: ByteArray, uBytes: ByteArray, vBytes: ByteArray,
        width: Int, height: Int,
        yRowStride: Int, uvRowStride: Int, uvPixelStride: Int
    ): ByteArray

    // runs 3x3 sobel edge detection on an rgba buffer
    external fun nativeSobelFilter(
        rgbaBytes: ByteArray, width: Int, height: Int
    ): ByteArray

    // neon warm-up — grayscale using uint8x16_t vectors (16 pixels at once)
    external fun nativeGrayscaleNeon(
        rgbaBytes: ByteArray, width: Int, height: Int
    ): ByteArray

    // phase 2 stage 2 — neon-accelerated sobel edge detection (16 pixels per iteration)
    external fun nativeSobelNeon(
        rgbaBytes: ByteArray, width: Int, height: Int
    ): ByteArray

    // phase 2 stage 3 — compares neon sobel output against a scalar reference, returns "PASS: ..." / "FAIL: ..."
    external fun nativeVerifySobelCorrectness(
        rgbaBytes: ByteArray, width: Int, height: Int
    ): String

    // phase 3 stage 1 — spins up a headless EGL context and compiles the pass-through compute shader
    // must be called on the thread that will subsequently make OpenGL ES calls
    external fun nativeInitGpu(): String

    // phase 3 stage 1 — copies rgbaBytes through a GPU SSBO pass-through compute shader
    external fun nativeGpuPassThrough(
        rgbaBytes: ByteArray, width: Int, height: Int
    ): ByteArray

    // phase 3 stage 1 — verifies that the SSBO round-trip produces an exact copy, returns "GPU PASS/FAIL: ..."
    external fun nativeVerifyGpuPassThrough(
        rgbaBytes: ByteArray, width: Int, height: Int
    ): String

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
    var sobelLatencyMs      by remember { mutableStateOf(0L) }
    var jniLatencyMs        by remember { mutableStateOf(0L) }
    var endToEndLatencyMs   by remember { mutableStateOf(0L) }
    var cpuUsagePercent     by remember { mutableStateOf(0.0) }
    var frameIntervalMs     by remember { mutableStateOf(-1L) }

    // hold the latest rotated rgba bitmap for display
    var processedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var useSimd         by remember { mutableStateOf(false) }  // phase 2 stage 4 — false=Baseline, true=SIMD
    var simdCheckResult by remember { mutableStateOf<String?>(null) }

    // phase 2 stage 5 — track sobel latency per mode to compute speedup ratio
    var baselineSobelMs by remember { mutableStateOf(0L) }
    var neonSobelMs     by remember { mutableStateOf(0L) }

    // phase 3 stage 1 — GPU init + pass-through SSBO verification result
    var gpuInitResult by remember { mutableStateOf<String?>(null) }
    val gpuInitDone   = remember { booleanArrayOf(false) }  // run only once, on first frame


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
    val verifyOnce = remember { booleanArrayOf(false) }  // run neon check on first frame only

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

                // inter-frame interval from hardware timestamps; ~33 ms at 30 fps
                val hwTimestampNs = image.timestamp
                val intervalMs = if (lastHwTimestampNs[0] > 0L)
                    (hwTimestampNs - lastHwTimestampNs[0]) / 1_000_000L else -1L
                lastHwTimestampNs[0] = hwTimestampNs

                Log.d(TAG, "frame: hw=${hwTimestampNs / 1_000_000}ms  interval=${intervalMs}ms")
                if (intervalMs > 40L) Log.w(TAG, "frame gap ${intervalMs}ms — possible dropped frame")

                val processingStart = System.currentTimeMillis()

                // copy yuv planes into byte arrays for jni
                val yPlane = image.planes[0]
                val uPlane = image.planes[1]
                val vPlane = image.planes[2]
                val yBytes = ByteArray(yPlane.buffer.remaining()).also { yPlane.buffer.get(it) }
                val uBytes = ByteArray(uPlane.buffer.remaining()).also { uPlane.buffer.get(it) }
                val vBytes = ByteArray(vPlane.buffer.remaining()).also { vPlane.buffer.get(it) }

                val convStart = System.currentTimeMillis()
                val rgbaBytes = activity.nativeYuvToRgba(
                    yBytes, uBytes, vBytes,
                    image.width, image.height,
                    yPlane.rowStride, uPlane.rowStride, uPlane.pixelStride
                )
                val convLatency = System.currentTimeMillis() - convStart

                // phase 2 stage 3 — one shot neon correctness check on the first frame
                if (!verifyOnce[0]) {
                    verifyOnce[0] = true
                    val result = activity.nativeVerifySobelCorrectness(rgbaBytes, image.width, image.height)
                    Log.d(TAG, "simd check: $result")
                    mainHandler.post { simdCheckResult = result }
                }

                // phase 3 stage 1 — initialize the headless EGL context on this background thread
                // then immediately verify SSBO round-trip correctness with the pass-through shader
                // must happen here (not in onCreate) so the EGL context is bound to this thread
                if (!gpuInitDone[0]) {
                    gpuInitDone[0] = true
                    val initMsg = activity.nativeInitGpu()
                    Log.d(TAG, "gpu init: $initMsg")
                    val verifyMsg = if (initMsg.startsWith("GPU OK"))
                        activity.nativeVerifyGpuPassThrough(rgbaBytes, image.width, image.height)
                    else
                        initMsg  // propagate the init failure as the verify result
                    Log.d(TAG, "gpu verify: $verifyMsg")
                    mainHandler.post { gpuInitResult = verifyMsg }
                }

                // phase 2 stage 4 — route to neon or scalar based on toggle
                val sobelStart = System.currentTimeMillis()
                val edgeBytes = if (useSimd)
                    activity.nativeSobelNeon(rgbaBytes, image.width, image.height)
                else
                    activity.nativeSobelFilter(rgbaBytes, image.width, image.height)
                val sobelLat = System.currentTimeMillis() - sobelStart

                // build bitmap from rgba bytes and rotate 90° to match display orientation
                val rawBmp = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
                rawBmp.copyPixelsFromBuffer(ByteBuffer.wrap(edgeBytes))
                val matrix = Matrix().apply { postRotate(90f) }
                val bmp = Bitmap.createBitmap(rawBmp, 0, 0, rawBmp.width, rawBmp.height, matrix, true)
                rawBmp.recycle()

                val procLatency = System.currentTimeMillis() - processingStart
                val e2eLatency  = System.currentTimeMillis() - frameArrivalTime

                mainHandler.post {
                    currentFps          = fps
                    processingLatencyMs = procLatency
                    conversionLatencyMs = convLatency
                    sobelLatencyMs      = sobelLat
                    jniLatencyMs        = convLatency + sobelLat
                    endToEndLatencyMs   = e2eLatency
                    frameIntervalMs     = intervalMs
                    processedBitmap     = bmp
                    // phase 2 stage 5 — store latency per mode for speedup ratio
                    if (useSimd) neonSobelMs = sobelLat else baselineSobelMs = sobelLat
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

    Box(modifier = Modifier
        .fillMaxSize()
        .background(Color.Black)){
        // hidden textureview - still needed to drive the camera pipeline
        // size 0.dp makes it visible but still receives frames

        AndroidView(
            modifier = Modifier.size(0.dp),
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

        // display the edge-detected frame (whichever sobel path is active)
        processedBitmap?.let { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }

        // phase 2 stage 4 — toggle between scalar baseline and neon simd
        Button(
            onClick = { useSimd = !useSimd },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
        ) {
            Text(if (useSimd) "Switch to Baseline" else "Switch to SIMD")
        }

        // semi-transparent hud pinned to top-left corner
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            // mode header
            Text(
                text = if (useSimd) "MODE: SIMD" else "MODE: Baseline",
                color = if (useSimd) Color.Green else Color.Cyan,
                fontSize = 13.sp, fontWeight = FontWeight.Bold
            )

            Divider(color = Color.Gray.copy(alpha = 0.5f), thickness = 0.5.dp)

            //  performance
            val fpsColor = when {
                currentFps >= 25.0 -> Color.Green
                currentFps >= 15.0 -> Color.Yellow
                else               -> Color.Red
            }
            Text("FPS:  ${String.format("%.1f", currentFps)}", color = fpsColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)

            val intervalDisplay = if (frameIntervalMs < 0L) "--" else "${frameIntervalMs} ms"
            Text(
                text  = "Intv: $intervalDisplay",
                color = if (frameIntervalMs < 0L || frameIntervalMs in 28L..38L) Color.White else Color.Yellow,
                fontSize = 13.sp
            )

            Divider(color = Color.Gray.copy(alpha = 0.5f), thickness = 0.5.dp)

            // latency breakdown
            Text("JNI:   ${jniLatencyMs} ms",          color = Color.Cyan,  fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text("Conv:  ${conversionLatencyMs} ms",   color = Color.White, fontSize = 13.sp)
            Text("Sobel: ${sobelLatencyMs} ms",        color = Color.White, fontSize = 13.sp)
            Text("E2E:   ${endToEndLatencyMs} ms",     color = Color.White, fontSize = 13.sp)

            Divider(color = Color.Gray.copy(alpha = 0.5f), thickness = 0.5.dp)

            // neon verification badge
            simdCheckResult?.let {
                val ok = it.startsWith("PASS")
                Text(
                    if (ok) "SIMD: PASS " else "SIMD: FAIL ",
                    color = if (ok) Color.Green else Color.Red,
                    fontSize = 13.sp, fontWeight = FontWeight.Bold
                )
            }

            // phase 3 stage 1 — gpu context + ssbo pass-through verification badge
            gpuInitResult?.let {
                val ok = it.startsWith("GPU PASS")
                Text(
                    if (ok) "GPU:  PASS " else "GPU:  FAIL ",
                    color = if (ok) Color.Green else Color.Red,
                    fontSize = 13.sp, fontWeight = FontWeight.Bold
                )
            }

            // phase 2 stage 5 — speedup ratio (shown once both modes have been sampled)
            if (baselineSobelMs > 0 && neonSobelMs > 0) {
                Divider(color = Color.Gray.copy(alpha = 0.5f), thickness = 0.5.dp)
                Text("Base:  ${baselineSobelMs} ms", color = Color.Cyan,  fontSize = 13.sp)
                Text("NEON:  ${neonSobelMs} ms",     color = Color.Green, fontSize = 13.sp)
                val ratio = baselineSobelMs.toDouble() / neonSobelMs.toDouble()
                Text(
                    text = "Speedup: ${String.format("%.1f", ratio)}×",
                    color = Color.Yellow,
                    fontSize = 13.sp, fontWeight = FontWeight.Bold
                )
            }

            Divider(color = Color.Gray.copy(alpha = 0.5f), thickness = 0.5.dp)

            // system
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
