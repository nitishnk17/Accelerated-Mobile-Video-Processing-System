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
import android.util.Range
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
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
import kotlin.math.min

// logcat tag — filter by "CSProject" in android studio
private const val TAG = "CSProject"
private const val MODE_COUNT = 5  // 0=Base 1=SIMD 2=GPU 3=Hyb 4=Raw (no-filter passthrough, UI-leftmost)
private const val SPEEDUP_WARMUP_FRAMES = 3
private const val SPEEDUP_MAX_SAMPLES = 30

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

    // phase 4 stage 4 — times the image2D Sobel workgroup-size variants on the real
    // device and latches the fastest one for subsequent nativeGpuSobel/nativeHybridSobel calls.
    // returns a "GPU BENCH: ..." status string.
    external fun nativeBenchmarkGpuVariants(
        rgbaBytes: ByteArray, width: Int, height: Int
    ): String

    // phase 4 stage 5 — peek at the adaptive hybrid split state. writes into a
    // caller-owned long[3]: [midRow, lastNeonHalfNs, lastGpuHalfNs]. takes the
    // buffer as a param so we don't allocate a fresh LongArray every hybrid frame.
    external fun nativeGetHybridStats(out: LongArray)

    // phase 5 — lightweight runtime hints for the native hybrid controller so it
    // can react to sustained heat and latency without the kotlin side micromanaging
    // the split policy itself.
    external fun nativeSetRuntimeHints(tempC: Float, e2eNs: Long, jitterCount: Int)

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
    // hud expand/collapse — when false, only the mode + fps header stays on
    // screen so the camera feed isn't crowded out during a demo
    var hudExpanded     by remember { mutableStateOf(true) }
    var simdCheckResult by remember { mutableStateOf<String?>(null) }

    // speedup uses stabilized per-mode running averages instead of raw single-frame
    // timings. otherwise "Base" keeps moving when you switch back to baseline while
    // the accelerated rows are stale snapshots from earlier frames/modes.
    var baselineSobelNs by remember { mutableStateOf(0L) }
    var neonSobelNs     by remember { mutableStateOf(0L) }
    var gpuSobelNs      by remember { mutableStateOf(0L) }
    var hybridSobelNs   by remember { mutableStateOf(0L) }
    var baselineSamples by remember { mutableStateOf(0) }
    var neonSamples     by remember { mutableStateOf(0) }
    var gpuSamples      by remember { mutableStateOf(0) }
    var hybridSamples   by remember { mutableStateOf(0) }

    // phase 4 stage 5 — adaptive hybrid split telemetry, pulled from native once per
    // hybrid frame. midRow == 0 means "haven't seen a hybrid frame yet" — we gate
    // the dashboard row on that so cold-start doesn't flash a meaningless "0 rows".
    var hybridMidRow     by remember { mutableStateOf(0) }
    var hybridNeonHalfNs by remember { mutableStateOf(0L) }
    var hybridGpuHalfNs  by remember { mutableStateOf(0L) }

    // resolution state — 720p default, toggles to 1080p
    var resW by remember { mutableStateOf(1280) }
    var resH by remember { mutableStateOf(720) }
    var isSwitchingRes by remember { mutableStateOf(false) }

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
    // phase 4 stage 4 — workgroup-variant benchmark result ("GPU BENCH: ... -> NxN wins")
    var gpuBenchResult by remember { mutableStateOf<String?>(null) }
    val gpuInitDone   = remember { booleanArrayOf(false) }  // run only once, on first frame

    // phase 4 stage 2 — pre-allocated buffers to eliminate per-frame GC pressure
    // rawBmp: un-rotated edge output; reused via copyPixelsFromBuffer (no allocation)
    val rawBmp = remember(resW, resH) { Bitmap.createBitmap(resW, resH, Bitmap.Config.ARGB_8888) }
    // double-buffered rotated bitmaps: background writes to back, UI reads front
    val rotBitmaps  = remember(resW, resH) {
        arrayOf(
            Bitmap.createBitmap(resH, resW, Bitmap.Config.ARGB_8888),
            Bitmap.createBitmap(resH, resW, Bitmap.Config.ARGB_8888)
        )
    }
    val rotCanvases = remember(resW, resH) { arrayOf(Canvas(rotBitmaps[0]), Canvas(rotBitmaps[1])) }
    // postRotate(90) maps (x,y)->(-y,x), which shifts the image to negative x.
    // postTranslate(H,0) brings it back: the image spans x=[0,H], y=[0,W]
    val rotMatrix   = remember(resW, resH) { Matrix().apply { postRotate(90f); postTranslate(resH.toFloat(), 0f) } }
    val backIdxRef  = remember { intArrayOf(0) }  // which rotBitmap the bg thread writes to next
    // lazily-sized yuv plane byte arrays; allocated on first frame, reused every frame after
    val yBufRef = remember { arrayOfNulls<ByteArray>(1) }
    // phase 4 stage 5 — preallocated scratch for nativeGetHybridStats so the jni
    // call doesn't allocate a fresh long[] per hybrid frame
    val hybridStatsBuf = remember { LongArray(3) }
    val uBufRef = remember { arrayOfNulls<ByteArray>(1) }
    val vBufRef = remember { arrayOfNulls<ByteArray>(1) }

    // need a reference to the activity to call the jni method
    val activity = context as MainActivity

    // non-reactive refs used only for cleanup on dispose
    val cameraDeviceRef   = remember { arrayOfNulls<CameraDevice>(1) }
    val captureSessionRef = remember { arrayOfNulls<CameraCaptureSession>(1) }
    val textureViewRef    = remember { arrayOfNulls<TextureView>(1) }
    val cameraOpenGenRef  = remember { intArrayOf(0) }

    // background thread for all camera2 callbacks; keeps ui thread free
    val backgroundThread  = remember { HandlerThread("Camera2Background").also { it.start() } }
    val backgroundHandler = remember { Handler(backgroundThread.looper) }
    val mainHandler       = remember { Handler(Looper.getMainLooper()) }

    // receives raw yuv_420_888 frames; maxImages=2 prevents stalls
    val imageReader = remember(resW, resH) {
        ImageReader.newInstance(resW, resH, ImageFormat.YUV_420_888, 2)
    }
    // ensure old ImageReader is closed when resolution changes
    DisposableEffect(imageReader) {
        onDispose { imageReader.close() }
    }

    // rolling window of last 30 frame arrival timestamps for stable fps
    val frameTimestamps = remember { ArrayDeque<Long>() }

    // previous frame hardware timestamp (ns); longarray lets the lambda mutate it
    val lastHwTimestampNs = remember { longArrayOf(-1L) }
    val verifyOnce = remember { booleanArrayOf(false) }  // run neon check on first frame only
    val modeWarmupFrames = remember { IntArray(MODE_COUNT) }
    val modeSampleCounts = remember { IntArray(MODE_COUNT) }
    val modeAveragesNs   = remember { LongArray(MODE_COUNT) }

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

    DisposableEffect(resW, resH) {
        imageReader.setOnImageAvailableListener({ reader ->
            if (isSwitchingRes) {
                reader.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener

            // ATOMIC CHECK: If the image width doesn't match our current resW,
            // it's a "ghost frame" from the previous resolution. DROP IT.
            if (image.width != resW || image.height != resH) {
                image.close()
                return@setOnImageAvailableListener
            }

            try {
                // phase 4 stage 1 — nanosecond capture timestamp; drives e2e latency
                val frameArrivalNs = System.nanoTime()
                // currentTimeMillis still used for the fps rolling window (wall-clock ms is fine there)
                val frameArrivalWallMs = System.currentTimeMillis()
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

                if (rgbaBytes.isEmpty()) return@setOnImageAvailableListener

                // phase 2 stage 3 — one shot neon correctness check on the first frame
                if (!verifyOnce[0]) {
                    verifyOnce[0] = true
                    val result = activity.nativeVerifySobelCorrectness(rgbaBytes, image.width, image.height)
                    mainHandler.post { simdCheckResult = result }
                }

                // phase 3 stage 1 — initialize the headless EGL context on this background thread
                // then immediately verify SSBO round-trip correctness with the pass-through shader
                // must happen here (not in onCreate) so the EGL context is bound to this thread
                if (!gpuInitDone[0]) {
                    gpuInitDone[0] = true
                    val initMsg = activity.nativeInitGpu()
                    val verifyMsg = if (initMsg.startsWith("GPU OK"))
                        activity.nativeVerifyGpuPassThrough(rgbaBytes, image.width, image.height)
                    else
                        initMsg  // propagate the init failure as the verify result
                    mainHandler.post { gpuInitResult = verifyMsg }

                    // only bother checking sobel correctness if the ssbo pipeline itself works
                    if (verifyMsg.startsWith("GPU PASS")) {
                        // phase 4 stage 4 — race the image2D sobel workgroup variants before
                        // correctness verification runs, so whatever verify passes/fails on is
                        // the exact variant the live pipeline will dispatch for every subsequent frame
                        val benchMsg = activity.nativeBenchmarkGpuVariants(rgbaBytes, image.width, image.height)
                        mainHandler.post { gpuBenchResult = benchMsg }

                        val sobelVerify = activity.nativeVerifyGpuSobel(rgbaBytes, image.width, image.height)
                        mainHandler.post { gpuSobelCheckResult = sobelVerify }
                    }
                }

                // grab mode once so a button tap mid-frame can't mix two paths
                var currentMode = mode
                // gpu/hybrid before egl init would return an empty buffer -> black flash
                if ((currentMode == 2 || currentMode == 3) && !gpuInitDone[0]) {
                    currentMode = 0
                }

                val oldMode = lastModeRef[0]
                val modeChanged = currentMode != oldMode
                lastModeRef[0] = currentMode
                if (modeChanged) {
                    modeWarmupFrames[currentMode] = SPEEDUP_WARMUP_FRAMES
                }

                // stage 3: sobel (active mode)
                val sobelStartNs = System.nanoTime()
                // route frame to active mode: 0=Baseline  1=SIMD  2=GPU  3=Hybrid  4=Raw
                // raw just hands the camera rgba back — no jni, no copy. that tiny sobelNs
                // reading you'll see for mode 4 is literally the cost of this when-dispatch
                val edgeBytes = when (currentMode) {
                    1    -> activity.nativeSobelNeon(rgbaBytes, image.width, image.height)
                    2    -> activity.nativeGpuSobel(rgbaBytes, image.width, image.height)
                    3    -> activity.nativeHybridSobel(rgbaBytes, image.width, image.height)
                    4    -> rgbaBytes
                    else -> activity.nativeSobelFilter(rgbaBytes, image.width, image.height)
                }
                val sobelNsVal = System.nanoTime() - sobelStartNs

                if (edgeBytes.isEmpty()) return@setOnImageAvailableListener

                // phase 4 stage 5 — only probe hybrid stats on the frame we actually
                // ran hybrid on. one jni call, three longs into a preallocated scratch
                // buffer — zero per-frame garbage.
                val haveHybridStats = currentMode == 3
                var hybridMidRowSnap = 0
                var hybridNeonSnap   = 0L
                var hybridGpuSnap    = 0L
                if (haveHybridStats) {
                    activity.nativeGetHybridStats(hybridStatsBuf)
                    hybridMidRowSnap = hybridStatsBuf[0].toInt()
                    hybridNeonSnap   = hybridStatsBuf[1]
                    hybridGpuSnap    = hybridStatsBuf[2]
                }

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
                    // SPEEDUP uses stabilized per-mode averages. skip a few frames after
                    // each mode switch so camera/session/GPU pipeline transitions don't
                    // poison the numbers. cap samples so old history decays naturally.
                    if (modeWarmupFrames[currentMode] > 0) {
                        modeWarmupFrames[currentMode]--
                    } else {
                        val prevAvg = modeAveragesNs[currentMode]
                        val prevCount = modeSampleCounts[currentMode]
                        val nextCount = min(prevCount + 1, SPEEDUP_MAX_SAMPLES)
                        val nextAvg = if (prevCount == 0) {
                            sobelNsVal
                        } else if (prevCount < SPEEDUP_MAX_SAMPLES) {
                            ((prevAvg * prevCount.toLong()) + sobelNsVal) / nextCount.toLong()
                        } else {
                            ((prevAvg * (SPEEDUP_MAX_SAMPLES - 1).toLong()) + sobelNsVal) /
                                SPEEDUP_MAX_SAMPLES.toLong()
                        }
                        modeAveragesNs[currentMode] = nextAvg
                        modeSampleCounts[currentMode] = nextCount
                    }

                    baselineSobelNs = modeAveragesNs[0]
                    neonSobelNs     = modeAveragesNs[1]
                    gpuSobelNs      = modeAveragesNs[2]
                    hybridSobelNs   = modeAveragesNs[3]
                    baselineSamples = modeSampleCounts[0]
                    neonSamples     = modeSampleCounts[1]
                    gpuSamples      = modeSampleCounts[2]
                    hybridSamples   = modeSampleCounts[3]
                    val nextJitterCount = jitterCount + if (isJitter) 1 else 0
                    if (modeChanged) transitionCount++
                    if (droppedOnSwitch) droppedDuringTransition++
                    if (isJitter) jitterCount = nextJitterCount
                    activity.nativeSetRuntimeHints(thermalTempC.toFloat(), e2eNsVal, nextJitterCount)
                    // publish the adaptive split telemetry (hybrid only) — using the
                    // bg-thread snapshot, never the shared scratch buffer
                    if (haveHybridStats) {
                        hybridMidRow     = hybridMidRowSnap
                        hybridNeonHalfNs = hybridNeonSnap
                        hybridGpuHalfNs  = hybridGpuSnap
                    }
                }
            } finally {
                image.close() // must close every image or camera stalls
            }
        }, backgroundHandler)

        onDispose {
            imageReader.setOnImageAvailableListener(null, null)
        }
    }

    val restartCamera: (String) -> Unit = fun(reason: String) {
        val textureView = textureViewRef[0]
        val surfaceTexture = textureView?.surfaceTexture
        if (textureView == null || surfaceTexture == null || !textureView.isAvailable) {
            Log.d(TAG, "skip camera restart ($reason): surface not ready")
            return
        }

        val openGeneration = ++cameraOpenGenRef[0]
        Log.d(TAG, "restartCamera[$openGeneration]: $reason -> ${resW}x$resH")
        captureSessionRef[0]?.close()
        captureSessionRef[0] = null
        cameraDeviceRef[0]?.close()
        cameraDeviceRef[0] = null

        openCamera(
            context = context,
            surfaceTexture = surfaceTexture,
            imageReader = imageReader,
            backgroundHandler = backgroundHandler,
            resW = resW,
            resH = resH,
            openGeneration = openGeneration,
            currentGeneration = { cameraOpenGenRef[0] },
            onCameraOpened = { cameraDeviceRef[0] = it },
            onSessionCreated = {
                captureSessionRef[0] = it
                mainHandler.post {
                    isSwitchingRes = false
                }
            },
            onCameraFailed = {
                mainHandler.post {
                    isSwitchingRes = false
                }
            }
        )
    }
    val latestRestartCamera by rememberUpdatedState(restartCamera)

    LaunchedEffect(resW, resH, imageReader) {
        gpuInitDone[0] = false
        verifyOnce[0] = false
        lastHwTimestampNs[0] = -1L
        frameTimestamps.clear()
        for (i in 0 until MODE_COUNT) {
            modeWarmupFrames[i] = SPEEDUP_WARMUP_FRAMES
            modeSampleCounts[i] = 0
            modeAveragesNs[i] = 0L
        }
        baselineSobelNs = 0L
        neonSobelNs = 0L
        gpuSobelNs = 0L
        hybridSobelNs = 0L
        baselineSamples = 0
        neonSamples = 0
        gpuSamples = 0
        hybridSamples = 0
        if (textureViewRef[0]?.isAvailable == true) {
            restartCamera("resolution changed")
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            cameraOpenGenRef[0]++
            imageReader.setOnImageAvailableListener(null, null)
            captureSessionRef[0]?.close()
            captureSessionRef[0] = null
            cameraDeviceRef[0]?.close()
            cameraDeviceRef[0] = null
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
                textureViewRef[0] = textureView
                textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                        latestRestartCamera("surface available")
                    }
                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                        cameraOpenGenRef[0]++
                        captureSessionRef[0]?.close()
                        captureSessionRef[0] = null
                        cameraDeviceRef[0]?.close()
                        cameraDeviceRef[0] = null
                        return true
                    }
                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                }
                textureView
            },
            update = { textureView ->
                textureViewRef[0] = textureView
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

        // active mode color drives every accent on screen — the hud's left bar,
        // the mode header, and whichever segmented pill is currently filled.
        // one source of truth so a glance tells you which path is live.
        val accent = modeAccent(mode)

        // top-left hud card. the 3dp accent stripe is a sibling of the metrics
        // column inside an IntrinsicSize.Min row, so it stretches to whatever
        // height the content lands at — no drawBehind, no remeasure dance.
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
                .clip(CardShape)
                .background(Color.Black.copy(alpha = 0.55f))
                .height(IntrinsicSize.Min)
        ) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(accent))

            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                // ── header: mode name + the hero fps number + collapse toggle ──
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = modeName(mode),
                        color = accent,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.width(14.dp))
                    val fpsColor = when {
                        currentFps >= 25.0 -> Color.Green
                        currentFps >= 15.0 -> Color.Yellow
                        else               -> Color.Red
                    }
                    Text(
                        text = String.format("%.0f", currentFps),
                        color = fpsColor,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(" fps", color = LabelGray, fontSize = 10.sp)
                    Spacer(Modifier.width(12.dp))
                    // tiny chevron toggle — ▾ when expanded, ▸ when collapsed.
                    // big invisible padding makes the tap target finger-friendly
                    // even though the glyph itself is small
                    Box(
                        modifier = Modifier
                            .clickable { hudExpanded = !hudExpanded }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (hudExpanded) "▾" else "▸",
                            color = LabelGray,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (hudExpanded) {

                // sub-header — e2e latency (the other hero) and frame interval.
                // interval goes yellow if it drifts outside the 28..38 ms window
                // around 30 fps, which usually means a dropped/late frame.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("E2E ", color = LabelGray, fontSize = 11.sp)
                    Text(
                        fmtNs(endToEndNs),
                        color = Color.Yellow,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.width(10.dp))
                    val intvOk = frameIntervalMs < 0L || frameIntervalMs in 28L..38L
                    Text(
                        text = "Δ " + if (frameIntervalMs < 0L) "--" else "${frameIntervalMs}ms",
                        color = if (intvOk) LabelGray else Color.Yellow,
                        fontSize = 11.sp
                    )
                }

                // ── PIPELINE — keep the main processing cost visible without the
                // low-level conversion/render breakdown that clutters the demo HUD ──
                SectionHeader("PIPELINE")
                MetricRow("Sobel", fmtNs(sobelNs), Color.Cyan, bold = true)

                // ── SPEEDUP — only meaningful once at least one accelerated mode has run ──
                if (baselineSamples > 0 && (neonSamples > 0 || gpuSamples > 0 || hybridSamples > 0)) {
                    SectionHeader("SPEEDUP")
                    MetricRow("Base", fmtNs(baselineSobelNs), Color.Cyan)
                    if (neonSamples > 0)
                        MetricRow("NEON", fmtNs(neonSobelNs), Color.Green)
                    if (gpuSamples > 0)
                        MetricRow("GPU ", fmtNs(gpuSobelNs), GpuOrange)
                    if (hybridSamples > 0) {
                        MetricRow("Hyb ", fmtNs(hybridSobelNs), Color.Magenta)
                        // phase 4 stage 5 — adaptive split readout. midRow == 0 is the
                        // "haven't seen a hybrid frame yet" sentinel, so we gate on it.
                        // green once both halves finish within 10% of each other,
                        // yellow while the controller is still chasing the balance.
                        if (hybridMidRow > 0) {
                            val neonPct = (hybridMidRow * 100) / resH
                            val nH = hybridNeonHalfNs
                            val gH = hybridGpuHalfNs
                            val converged = nH > 0 && gH > 0 &&
                                kotlin.math.abs(nH - gH).toDouble() / kotlin.math.max(nH, gH) < 0.10
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("     split ", color = LabelGray, fontSize = 10.sp)
                                Text(
                                    "${neonPct}/${100 - neonPct}",
                                    color = if (converged) Color.Green else Color.Yellow,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    val bestNs = listOfNotNull(
                        if (neonSamples   > 0) neonSobelNs   else null,
                        if (gpuSamples    > 0) gpuSobelNs    else null,
                        if (hybridSamples > 0) hybridSobelNs else null
                    ).min()
                    val ratio = baselineSobelNs.toDouble() / bestNs.toDouble()
                    Text(
                        text = "${String.format("%.1f", ratio)}× vs baseline",
                        color = Color.Yellow,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 1.dp)
                    )
                }

                // ── SYSTEM — cpu / temp on one row, reliability counters on the next ──
                SectionHeader("SYSTEM")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val simdActive = mode == 1 || mode == 3
                    MetricRow(
                        "SIMD",
                        if (simdActive) "ACTIVE" else "IDLE",
                        if (simdActive) Color.Green else LabelGray,
                        bold = simdActive
                    )
                    Spacer(Modifier.width(10.dp))
                    val gpuActive = mode == 2 || mode == 3
                    MetricRow(
                        "GPU",
                        if (gpuActive) "ACTIVE" else "IDLE",
                        if (gpuActive) GpuOrange else LabelGray,
                        bold = gpuActive
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val tempColor = when {
                        thermalTempC < 0    -> Color.White
                        thermalTempC < 40.0 -> Color.Green
                        thermalTempC < 45.0 -> Color.Yellow
                        else                -> Color.Red
                    }
                    MetricRow(
                        "Temp",
                        if (thermalTempC < 0) "--" else "${String.format("%.1f", thermalTempC)}°C",
                        tempColor
                    )
                    Spacer(Modifier.width(10.dp))
                    MetricRow(
                        "Jit", "$jitterCount",
                        if (jitterCount < 10) Color.Green else Color.Yellow,
                        bold = true
                    )
                }

                } // end of if (hudExpanded)
            }
        }

        // resolution toggle in the top-right corner
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
                .clip(CardShape)
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(4.dp)
        ) {
            listOf(720, 1080).forEach { h ->
                val w = if (h == 720) 1280 else 1920
                val selected = resH == h
                Box(
                    modifier = Modifier
                        .clip(BadgeShape)
                        .background(if (selected) GpuOrange else Color.Transparent)
                        .clickable(enabled = !selected && !isSwitchingRes) {
                            isSwitchingRes = true
                            processedBitmap = null
                            resW = w
                            resH = h
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "${w}x${h}",
                        color = if (selected) Color.Black else Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // segmented mode switcher pinned to bottom-center. one tap per mode
        // beats the old cycle button — and stress-tap testing still works,
        // just mash any pill and watch Switches climb.
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 28.dp)
                .clip(PillShape)
                .background(Color.Black.copy(alpha = 0.6f))
        ) {
            // raw sits leftmost for eyeball comparison — tap it to see the un-sobeled camera,
            // tap any other pill to see the filter. ui order != index order (raw is index 4) so
            // every existing mode-index check downstream keeps working without a rewrite
            ModePill("Raw",  4, mode) { mode = 4 }
            ModePill("Base", 0, mode) { mode = 0 }
            ModePill("SIMD", 1, mode) { mode = 1 }
            ModePill("GPU",  2, mode) { mode = 2 }
            ModePill("Hyb",  3, mode) { mode = 3 }
        }
    }
}

// phase 4 stage 1 — formats a nanosecond duration as milliseconds (2 decimal places)
fun fmtNs(ns: Long): String = "${String.format("%.2f", ns / 1_000_000.0)} ms"

// hoisted shapes/colors for the dashboard. top-level vals so the hud (which
// recomposes ~30x/s) doesn't re-allocate a fresh Shape or Color every frame.
private val CardShape  = RoundedCornerShape(10.dp)
private val PillShape  = RoundedCornerShape(20.dp)
private val BadgeShape = RoundedCornerShape(4.dp)
private val LabelGray  = Color(0xFFB0B0B0)   // metric labels (slightly brighter)
private val DimGray    = Color(0xFF9E9E9E)   // section header dimmer
private val GpuOrange  = Color(0xFFFF9800)
private val PassGreen  = Color(0xFF1B5E20)
private val FailRed    = Color(0xFFB71C1C)

private fun modeAccent(mode: Int): Color = when (mode) {
    0    -> Color.Cyan
    1    -> Color.Green
    2    -> GpuOrange
    3    -> Color.Magenta
    else -> Color.White         // raw — neutral, reads as "no processing applied"
}

private fun modeName(mode: Int): String = when (mode) {
    0    -> "BASELINE"
    1    -> "SIMD"
    2    -> "GPU"
    3    -> "HYBRID"
    else -> "RAW"
}

// tiny uppercase divider — replaces the bare 0.5dp lines from the old hud.
// inputs are stable so compose smart-skips this whenever the label is unchanged
@Composable
private fun SectionHeader(text: String) {
    Text(
        text       = text,
        color      = DimGray,
        fontSize   = 9.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier   = Modifier.padding(top = 4.dp)
    )
}

// label + value on one row. all params are stable types so compose can skip
// rows whose value string didn't change this frame — only the few metrics
// that actually move (FPS, Sobel, E2E…) re-execute, the rest stay cached
@Composable
private fun MetricRow(
    label: String,
    value: String,
    valueColor: Color = Color.White,
    bold: Boolean = false
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = LabelGray, fontSize = 11.sp)
        Spacer(Modifier.width(4.dp))
        Text(
            text = value,
            color = valueColor,
            fontSize = 12.sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal
        )
    }
}

// little colored chip for the VERIFY block. green = check passed, red = check failed
@Composable
private fun StatusPill(label: String, ok: Boolean) {
    Box(
        modifier = Modifier
            .background((if (ok) PassGreen else FailRed).copy(alpha = 0.85f), BadgeShape)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(label, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

// one cell of the segmented mode switcher. the active pill fills with the
// mode's accent color, the others stay transparent on top of the row's
// shared dark background — that one shared bg is what gives the segmented
// look without us having to draw any borders between cells
@Composable
private fun ModePill(label: String, idx: Int, active: Int, onClick: () -> Unit) {
    val isActive = idx == active
    Box(
        modifier = Modifier
            .clickable(onClick = onClick)
            .background(if (isActive) modeAccent(idx).copy(alpha = 0.85f) else Color.Transparent)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text       = label,
            color      = if (isActive) Color.Black else Color.White.copy(alpha = 0.75f),
            fontSize   = 12.sp,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
        )
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
    resW: Int,
    resH: Int,
    openGeneration: Int,
    currentGeneration: () -> Int,
    onCameraOpened: (CameraDevice) -> Unit,
    onSessionCreated: (CameraCaptureSession) -> Unit,
    onCameraFailed: () -> Unit
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
    val targetFpsRange: Range<Int>? = availableFpsRanges?.firstOrNull { it.lower == 30 && it.upper == 30 }
                          ?: availableFpsRanges?.maxByOrNull { it.upper }
    Log.d(TAG, "fps ranges: ${availableFpsRanges?.toList()} -> selected: $targetFpsRange")

    surfaceTexture.setDefaultBufferSize(resW, resH)
    val previewSurface = Surface(surfaceTexture)

    cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {

        override fun onOpened(camera: CameraDevice) {
            if (openGeneration != currentGeneration()) {
                Log.d(TAG, "ignoring stale camera open [$openGeneration]")
                camera.close()
                onCameraFailed()
                return
            }
            onCameraOpened(camera)
            val outputs = listOf(previewSurface, imageReader.surface)

            camera.createCaptureSession(outputs, object : CameraCaptureSession.StateCallback() {

                override fun onConfigured(session: CameraCaptureSession) {
                    if (openGeneration != currentGeneration()) {
                        Log.d(TAG, "ignoring stale capture session [$openGeneration]")
                        session.close()
                        camera.close()
                        onCameraFailed()
                        return
                    }
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
                    session.close()
                    camera.close()
                    onCameraFailed()
                }
            }, backgroundHandler)
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            onCameraFailed()
        }

        override fun onError(camera: CameraDevice, errorCode: Int) {
            Log.e(TAG, "camera error: $errorCode")
            camera.close()
            onCameraFailed()
        }
    }, backgroundHandler)
}
