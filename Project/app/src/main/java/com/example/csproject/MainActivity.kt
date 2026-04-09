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
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.graphics.Bitmap
import android.graphics.Canvas
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

    // phase 3 stage 2 — runs Sobel edge detection on the GPU via compute shader + SSBOs
    external fun nativeGpuSobel(
        rgbaBytes: ByteArray, width: Int, height: Int
    ): ByteArray

    // phase 3 stage 3: checks if the gpu sobel output actually matches the scalar baseline
    external fun nativeVerifyGpuSobel(
        rgbaBytes: ByteArray, width: Int, height: Int
    ): String

    // phase 3 stage 4: hybrid sobel — NEON top half + GPU bottom half concurrently
    external fun nativeHybridSobel(
        rgbaBytes: ByteArray, width: Int, height: Int
    ): ByteArray

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
    var currentFps       by remember { mutableStateOf(0.0) }
    // phase 4 stage 1 — nanosecond-precision per-stage latency breakdown
    var yuvExtractNs     by remember { mutableStateOf(0L) }
    var conversionNs     by remember { mutableStateOf(0L) }
    var sobelNs          by remember { mutableStateOf(0L) }
    var bitmapCreateNs   by remember { mutableStateOf(0L) }
    var bitmapRotateNs   by remember { mutableStateOf(0L) }
    var endToEndNs       by remember { mutableStateOf(0L) }
    var cpuUsagePercent  by remember { mutableStateOf(0.0) }
    var frameIntervalMs  by remember { mutableStateOf(-1L) }

    // hold the latest rotated rgba bitmap for display
    var processedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    // 0=Baseline  1=SIMD  2=GPU  3=Hybrid — cycles on button tap
    var mode            by remember { mutableStateOf(0) }
    var simdCheckResult by remember { mutableStateOf<String?>(null) }

    // track sobel latency per mode for speedup comparison (ns)
    var baselineSobelNs by remember { mutableStateOf(0L) }
    var neonSobelNs     by remember { mutableStateOf(0L) }
    var gpuSobelNs      by remember { mutableStateOf(0L) }
    var hybridSobelNs   by remember { mutableStateOf(0L) }

    // phase 3 stage 5 — tracks mode switches and any frame drops that happen mid-transition
    var transitionCount         by remember { mutableStateOf(0) }
    var droppedDuringTransition by remember { mutableStateOf(0) }
    val lastModeRef = remember { intArrayOf(0) }  // mutable from the callback, same trick as lastHwTimestampNs

    // phase 3 stage 6 — keeps an eye on frame time spikes and soc temperature under sustained load
    var jitterCount  by remember { mutableStateOf(0) }
    var thermalTempC by remember { mutableStateOf(-1.0) }
    val rollingE2eMs = remember { doubleArrayOf(0.0) }  // callback-mutable like lastHwTimestampNs

    // phase 3 stage 1 — GPU init + pass-through SSBO verification result
    var gpuInitResult by remember { mutableStateOf<String?>(null) }
    // phase 3 stage 3: did the gpu sobel pass or fail the correctness check
    var gpuSobelCheckResult by remember { mutableStateOf<String?>(null) }
    val gpuInitDone   = remember { booleanArrayOf(false) }  // run only once, on first frame

    // phase 4 stage 2 — pre-allocated buffers to eliminate per-frame GC pressure
    // rawBmp: un-rotated edge output; reused via copyPixelsFromBuffer (no allocation)
    val rawBmp = remember { Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888) }
    // double-buffered rotated bitmaps: background writes to back, UI reads front
    val rotBitmaps  = remember {
        arrayOf(
            Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888),
            Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888)
        )
    }
    val rotCanvases = remember { arrayOf(Canvas(rotBitmaps[0]), Canvas(rotBitmaps[1])) }
    // postRotate(90) maps (x,y)->(-y,x), which shifts the image to negative x.
    // postTranslate(720,0) brings it back: the image spans x=[0,720], y=[0,1280]
    val rotMatrix   = remember { Matrix().apply { postRotate(90f); postTranslate(720f, 0f) } }
    val backIdxRef  = remember { intArrayOf(0) }  // which rotBitmap the bg thread writes to next
    // lazily-sized yuv plane byte arrays; allocated on first frame, reused every frame after
    val yBufRef = remember { arrayOfNulls<ByteArray>(1) }
    val uBufRef = remember { arrayOfNulls<ByteArray>(1) }
    val vBufRef = remember { arrayOfNulls<ByteArray>(1) }

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
    // battery temp updates slowly so 2 s is plenty
    LaunchedEffect(Unit) {
        while (true) {
            thermalTempC = readBatteryTemp(context)
            delay(2000)
        }
    }
    DisposableEffect(Unit) {
        imageReader.setOnImageAvailableListener({ reader ->
            // phase 4 stage 1 — nanosecond capture timestamp; drives e2e latency
            val frameArrivalNs = System.nanoTime()
            // currentTimeMillis still used for the fps rolling window (wall-clock ms is fine there)
            val frameArrivalWallMs = System.currentTimeMillis()
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener

            try {
                // rolling 30-frame fps average
                frameTimestamps.addLast(frameArrivalWallMs)
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

                // stage 1: yuv plane extraction — reuse pre-allocated byte arrays (no GC alloc after first frame)
                val yuvExtractStart = System.nanoTime()
                val yPlane = image.planes[0]
                val uPlane = image.planes[1]
                val vPlane = image.planes[2]
                val ySize = yPlane.buffer.remaining()
                val uSize = uPlane.buffer.remaining()
                val vSize = vPlane.buffer.remaining()
                if (yBufRef[0]?.size != ySize) yBufRef[0] = ByteArray(ySize)
                if (uBufRef[0]?.size != uSize) uBufRef[0] = ByteArray(uSize)
                if (vBufRef[0]?.size != vSize) vBufRef[0] = ByteArray(vSize)
                val yBytes = yBufRef[0]!!.also { yPlane.buffer.get(it) }
                val uBytes = uBufRef[0]!!.also { uPlane.buffer.get(it) }
                val vBytes = vBufRef[0]!!.also { vPlane.buffer.get(it) }
                val yuvExtractNsVal = System.nanoTime() - yuvExtractStart

                // stage 2: yuv->rgba conversion (jni)
                val convStartNs = System.nanoTime()
                val rgbaBytes = activity.nativeYuvToRgba(
                    yBytes, uBytes, vBytes,
                    image.width, image.height,
                    yPlane.rowStride, uPlane.rowStride, uPlane.pixelStride
                )
                val convNsVal = System.nanoTime() - convStartNs

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

                    // only bother checking sobel correctness if the ssbo pipeline itself works
                    if (verifyMsg.startsWith("GPU PASS")) {
                        val sobelVerify = activity.nativeVerifyGpuSobel(rgbaBytes, image.width, image.height)
                        Log.d(TAG, "gpu sobel verify: $sobelVerify")
                        mainHandler.post { gpuSobelCheckResult = sobelVerify }
                    }
                }

                // grab mode once so a button tap mid-frame can't mix two paths
                var currentMode = mode
                // gpu/hybrid before egl init would return an empty buffer -> black flash
                if ((currentMode == 2 || currentMode == 3) && !gpuInitDone[0]) {
                    Log.w(TAG, "GPU not ready, falling back to Baseline for this frame")
                    currentMode = 0
                }

                val oldMode = lastModeRef[0]
                val modeChanged = currentMode != oldMode
                lastModeRef[0] = currentMode
                if (modeChanged) {
                    Log.d(TAG, "mode switch: $oldMode -> $currentMode")
                }

                // stage 3: sobel (active mode)
                val sobelStartNs = System.nanoTime()
                // route frame to active mode: 0=Baseline  1=SIMD  2=GPU  3=Hybrid
                val edgeBytes = when (currentMode) {
                    1    -> activity.nativeSobelNeon(rgbaBytes, image.width, image.height)
                    2    -> activity.nativeGpuSobel(rgbaBytes, image.width, image.height)
                    3    -> activity.nativeHybridSobel(rgbaBytes, image.width, image.height)
                    else -> activity.nativeSobelFilter(rgbaBytes, image.width, image.height)
                }
                val sobelNsVal = System.nanoTime() - sobelStartNs

                // stage 4: fill pre-allocated rawBmp with edge pixels — no Bitmap allocation
                val bmpCreateStartNs = System.nanoTime()
                rawBmp.copyPixelsFromBuffer(ByteBuffer.wrap(edgeBytes))
                val bmpCreateNsVal = System.nanoTime() - bmpCreateStartNs

                // stage 5: draw into the back-buffer rotated Bitmap via pre-allocated Canvas — no allocation
                val bmpRotateStartNs = System.nanoTime()
                val backIdx = backIdxRef[0]
                rotCanvases[backIdx].drawBitmap(rawBmp, rotMatrix, null)
                backIdxRef[0] = 1 - backIdx  // flip immediately so next frame uses the other buffer
                val bmpRotateNsVal = System.nanoTime() - bmpRotateStartNs

                // total e2e from camera frame arrival to display-ready
                val e2eNsVal = System.nanoTime() - frameArrivalNs

                // flag frames where e2e blows past 2x the running average (compare in ms)
                val e2eMs = e2eNsVal / 1_000_000.0
                val avg = rollingE2eMs[0]
                val isJitter = avg > 0.0 && e2eMs > avg * 2.0
                rollingE2eMs[0] = if (avg == 0.0) e2eMs else avg * 0.9 + e2eMs * 0.1
                if (isJitter) Log.w(TAG, "jitter: e2e=${String.format("%.2f", e2eMs)}ms vs avg=${String.format("%.2f", avg)}ms")

                val droppedOnSwitch = modeChanged && intervalMs > 40L

                mainHandler.post {
                    currentFps      = fps
                    yuvExtractNs    = yuvExtractNsVal
                    conversionNs    = convNsVal
                    sobelNs         = sobelNsVal
                    bitmapCreateNs  = bmpCreateNsVal
                    bitmapRotateNs  = bmpRotateNsVal
                    endToEndNs      = e2eNsVal
                    frameIntervalMs = intervalMs
                    processedBitmap = rotBitmaps[backIdx]  // show the just-written back buffer
                    // store sobel latency per mode for speedup comparison (ns)
                    when (currentMode) {
                        1    -> neonSobelNs     = sobelNsVal
                        2    -> gpuSobelNs      = sobelNsVal
                        3    -> hybridSobelNs   = sobelNsVal
                        else -> baselineSobelNs = sobelNsVal
                    }
                    if (modeChanged) transitionCount++
                    if (droppedOnSwitch) droppedDuringTransition++
                    if (isJitter) jitterCount++
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

        // cycle: Baseline (0) -> SIMD (1) -> GPU (2) -> Hybrid (3) -> Baseline (0)
        Button(
            onClick = { mode = (mode + 1) % 4 },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
        ) {
            val (current, next) = when (mode) {
                0    -> "Baseline" to "SIMD"
                1    -> "SIMD"     to "GPU"
                2    -> "GPU"      to "Hybrid"
                else -> "Hybrid"   to "Baseline"
            }
            Text("Active: $current -> Next: $next")
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
                text = when (mode) { 0 -> "MODE: Baseline"; 1 -> "MODE: SIMD"; 2 -> "MODE: GPU"; else -> "MODE: Hybrid" },
                color = when (mode) { 0 -> Color.Cyan; 1 -> Color.Green; 2 -> Color(0xFFFF9800); else -> Color.Magenta },
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

            // phase 4 stage 1 — nanosecond pipeline breakdown
            Text("YUV:    ${fmtNs(yuvExtractNs)}",  color = Color.White, fontSize = 13.sp)
            Text("Conv:   ${fmtNs(conversionNs)}",  color = Color.White, fontSize = 13.sp)
            Text("Sobel:  ${fmtNs(sobelNs)}",       color = Color.Cyan,  fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text("BmpMk:  ${fmtNs(bitmapCreateNs)}",color = Color.White, fontSize = 13.sp)
            Text("BmpRot: ${fmtNs(bitmapRotateNs)}",color = Color.White, fontSize = 13.sp)
            Text("E2E:    ${fmtNs(endToEndNs)}",    color = Color.Yellow,fontSize = 13.sp, fontWeight = FontWeight.Bold)

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

            // gpu sobel correctness badge: only shows up once the check has run
            gpuSobelCheckResult?.let {
                val ok = it.startsWith("GPU SOBEL PASS")
                Text(
                    if (ok) "GPU Sobel: PASS " else "GPU Sobel: FAIL ",
                    color = if (ok) Color.Green else Color.Red,
                    fontSize = 13.sp, fontWeight = FontWeight.Bold
                )
            }

            // speedup table — shown once at least two modes have been sampled (ns precision)
            if (baselineSobelNs > 0 && (neonSobelNs > 0 || gpuSobelNs > 0 || hybridSobelNs > 0)) {
                Divider(color = Color.Gray.copy(alpha = 0.5f), thickness = 0.5.dp)
                Text("Base:   ${fmtNs(baselineSobelNs)}", color = Color.Cyan,       fontSize = 13.sp)
                if (neonSobelNs > 0)
                    Text("NEON:   ${fmtNs(neonSobelNs)}", color = Color.Green,       fontSize = 13.sp)
                if (gpuSobelNs  > 0)
                    Text("GPU:    ${fmtNs(gpuSobelNs)}",  color = Color(0xFFFF9800), fontSize = 13.sp)
                if (hybridSobelNs > 0)
                    Text("Hybrid: ${fmtNs(hybridSobelNs)}", color = Color.Magenta,   fontSize = 13.sp)
                // show speedup vs baseline for whichever accelerated modes have run
                val bestNs = listOfNotNull(
                    if (neonSobelNs   > 0) neonSobelNs   else null,
                    if (gpuSobelNs    > 0) gpuSobelNs    else null,
                    if (hybridSobelNs > 0) hybridSobelNs else null
                ).min()
                val ratio = baselineSobelNs.toDouble() / bestNs.toDouble()
                Text(
                    text = "Speedup: ${String.format("%.1f", ratio)}×",
                    color = Color.Yellow,
                    fontSize = 13.sp, fontWeight = FontWeight.Bold
                )
            }

            Divider(color = Color.Gray.copy(alpha = 0.5f), thickness = 0.5.dp)

            // system
            Text("CPU:  ${String.format("%.1f", cpuUsagePercent)}%", color = Color.White, fontSize = 13.sp)

            // goes yellow/red as the soc heats up — red usually means throttling
            val tempDisplay = if (thermalTempC < 0) "--" else "${String.format("%.1f", thermalTempC)}°C"
            Text(
                text = "Temp: $tempDisplay",
                color = when {
                    thermalTempC < 0    -> Color.White
                    thermalTempC < 40.0 -> Color.Green
                    thermalTempC < 45.0 -> Color.Yellow
                    else                -> Color.Red
                },
                fontSize = 13.sp
            )

            // stress-test counters — tap the mode button rapidly and watch these
            Text("Switches: $transitionCount", color = Color.White, fontSize = 13.sp)
            Text(
                text = "Drop@Switch: $droppedDuringTransition",
                color = if (droppedDuringTransition == 0) Color.Green else Color.Red,
                fontSize = 13.sp, fontWeight = FontWeight.Bold
            )

            Text(
                text = "Jitter: $jitterCount",
                color = if (jitterCount < 10) Color.Green else Color.Yellow,
                fontSize = 13.sp, fontWeight = FontWeight.Bold
            )
        }
    }
}

// phase 4 stage 1 — formats a nanosecond duration as milliseconds (2 decimal places)
fun fmtNs(ns: Long): String = "${String.format("%.2f", ns / 1_000_000.0)} ms"

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

// battery temp via sticky broadcast — no permissions needed, works on all stock devices
// BatteryManager reports tenths of °C (e.g. 320 = 32.0°C); not cpu temp but tracks it under load
fun readBatteryTemp(context: Context): Double {
    val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val raw = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
    return if (raw > 0) raw / 10.0 else -1.0
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
    Log.d(TAG, "fps ranges: ${availableFpsRanges?.toList()} -> selected: $targetFpsRange")

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
