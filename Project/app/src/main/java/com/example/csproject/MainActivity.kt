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

// label for logs
private const val TAG = "CSProject"
// total number of modes
private const val MODE_COUNT = 5
// frames to wait before checking speed
private const val SPEEDUP_WARMUP_FRAMES = 3
// max frames to average
private const val SPEEDUP_MAX_SAMPLES = 30

class MainActivity : ComponentActivity() {

    // check native side status
    external fun nativeGetStatus(): String

    // turn camera data into pixels
    external fun nativeYuvToRgba(
        yBytes: ByteArray, uBytes: ByteArray, vBytes: ByteArray,
        width: Int, height: Int,
        yRowStride: Int, uvRowStride: Int, uvPixelStride: Int

    ): ByteArray

    // normal cpu edge detection
    external fun nativeSobelFilter(
        rgbaBytes: ByteArray, width: Int, height: Int
    ): ByteArray

    // test neon grayscale
    external fun nativeGrayscaleNeon(
        rgbaBytes: ByteArray, width: Int, height: Int
    ): ByteArray

    // fast cpu edge detection
    external fun nativeSobelNeon(
        rgbaBytes: ByteArray, width: Int, height: Int
    ): ByteArray

    // check if neon matches normal cpu
    external fun nativeVerifySobelCorrectness(
        rgbaBytes: ByteArray, width: Int, height: Int
    ): String

    // setup gpu
    external fun nativeInitGpu(): String

    // test gpu data move
    external fun nativeGpuPassThrough(
        rgbaBytes: ByteArray, width: Int, height: Int
    ): ByteArray

    // check if gpu data move works
    external fun nativeVerifyGpuPassThrough(
        rgbaBytes: ByteArray, width: Int, height: Int
    ): String

    // fast gpu edge detection
    external fun nativeGpuSobel(
        rgbaBytes: ByteArray, width: Int, height: Int
    ): ByteArray

    // check if gpu matches normal cpu
    external fun nativeVerifyGpuSobel(
        rgbaBytes: ByteArray, width: Int, height: Int
    ): String

    // use cpu and gpu together
    external fun nativeHybridSobel(
        rgbaBytes: ByteArray, width: Int, height: Int
    ): ByteArray

    // find best gpu settings
    external fun nativeBenchmarkGpuVariants(
        rgbaBytes: ByteArray, width: Int, height: Int
    ): String

    // get numbers for hybrid mode
    external fun nativeGetHybridStats(out: LongArray)

    // update native code with system info
    external fun nativeSetRuntimeHints(tempC: Float, e2eNs: Long, jitterCount: Int)

    companion object {
        init {
            // load native code
            System.loadLibrary("csproject")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // log startup status
        Log.d(TAG, nativeGetStatus())
        setContent { CameraApp() }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraApp() {
    // manage camera permission
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)

    if (cameraPermissionState.status.isGranted) {
        CameraScreen()
    } else {
        // ask for permission if missing
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("camera permission is required.")
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = { cameraPermissionState.launchPermissionRequest() }) {
                Text("request permission")
            }
        }
    }
}

@Composable
fun CameraScreen() {
    val context = LocalContext.current

    // track performance numbers
    var currentFps       by remember { mutableStateOf(0.0) }
    var yuvExtractNs     by remember { mutableStateOf(0L) }
    var conversionNs     by remember { mutableStateOf(0L) }
    var sobelNs          by remember { mutableStateOf(0L) }
    var bitmapCreateNs   by remember { mutableStateOf(0L) }
    var bitmapRotateNs   by remember { mutableStateOf(0L) }
    var endToEndNs       by remember { mutableStateOf(0L) }
    var cpuUsagePercent  by remember { mutableStateOf(0.0) }
    var frameIntervalMs  by remember { mutableStateOf(-1L) }

    // store the processed image
    var processedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    // current processing mode
    var mode            by remember { mutableStateOf(0) }
    // toggle dashboard view
    var hudExpanded     by remember { mutableStateOf(true) }
    var simdCheckResult by remember { mutableStateOf<String?>(null) }

    // keep track of averages
    var baselineSobelNs by remember { mutableStateOf(0L) }
    var neonSobelNs     by remember { mutableStateOf(0L) }
    var gpuSobelNs      by remember { mutableStateOf(0L) }
    var hybridSobelNs   by remember { mutableStateOf(0L) }
    var baselineSamples by remember { mutableStateOf(0) }
    var neonSamples     by remember { mutableStateOf(0) }
    var gpuSamples      by remember { mutableStateOf(0) }
    var hybridSamples   by remember { mutableStateOf(0) }

    // hybrid mode info
    var hybridMidRow     by remember { mutableStateOf(0) }
    var hybridNeonHalfNs by remember { mutableStateOf(0L) }
    var hybridGpuHalfNs  by remember { mutableStateOf(0L) }

    // image size settings
    var resW by remember { mutableStateOf(1280) }
    var resH by remember { mutableStateOf(720) }
    var isSwitchingRes by remember { mutableStateOf(false) }

    // stats on mode changes
    var transitionCount         by remember { mutableStateOf(0) }
    var droppedDuringTransition by remember { mutableStateOf(0) }
    val lastModeRef = remember { intArrayOf(0) }

    // thermal and timing stats
    var jitterCount  by remember { mutableStateOf(0) }
    var thermalTempC by remember { mutableStateOf(-1.0) }
    val rollingE2eMs = remember { doubleArrayOf(0.0) }

    // gpu status tracking
    var gpuInitResult by remember { mutableStateOf<String?>(null) }
    var gpuSobelCheckResult by remember { mutableStateOf<String?>(null) }
    var gpuBenchResult by remember { mutableStateOf<String?>(null) }
    val gpuInitDone   = remember { booleanArrayOf(false) }

    // setup memory for frames
    val rawBmp = remember(resW, resH) { Bitmap.createBitmap(resW, resH, Bitmap.Config.ARGB_8888) }
    val rotBitmaps  = remember(resW, resH) {
        arrayOf(
            Bitmap.createBitmap(resH, resW, Bitmap.Config.ARGB_8888),
            Bitmap.createBitmap(resH, resW, Bitmap.Config.ARGB_8888)
        )
    }
    val rotCanvases = remember(resW, resH) { arrayOf(Canvas(rotBitmaps[0]), Canvas(rotBitmaps[1])) }
    val rotMatrix   = remember(resW, resH) { Matrix().apply { postRotate(90f); postTranslate(resH.toFloat(), 0f) } }
    val backIdxRef  = remember { intArrayOf(0) }
    val yBufRef = remember { arrayOfNulls<ByteArray>(1) }
    val hybridStatsBuf = remember { LongArray(3) }
    val uBufRef = remember { arrayOfNulls<ByteArray>(1) }
    val vBufRef = remember { arrayOfNulls<ByteArray>(1) }

    val activity = context as MainActivity

    // camera handles
    val cameraDeviceRef   = remember { arrayOfNulls<CameraDevice>(1) }
    val captureSessionRef = remember { arrayOfNulls<CameraCaptureSession>(1) }
    val textureViewRef    = remember { arrayOfNulls<TextureView>(1) }
    val cameraOpenGenRef  = remember { intArrayOf(0) }

    // worker threads
    val backgroundThread  = remember { HandlerThread("Camera2Background").also { it.start() } }
    val backgroundHandler = remember { Handler(backgroundThread.looper) }
    val mainHandler       = remember { Handler(Looper.getMainLooper()) }

    // frame receiver
    val imageReader = remember(resW, resH) {
        ImageReader.newInstance(resW, resH, ImageFormat.YUV_420_888, 2)
    }
    // clean up reader
    DisposableEffect(imageReader) {
        onDispose { imageReader.close() }
    }

    // history of frame times
    val frameTimestamps = remember { ArrayDeque<Long>() }
    val lastHwTimestampNs = remember { longArrayOf(-1L) }
    val verifyOnce = remember { booleanArrayOf(false) }
    val modeWarmupFrames = remember { IntArray(MODE_COUNT) }
    val modeSampleCounts = remember { IntArray(MODE_COUNT) }
    val modeAveragesNs   = remember { LongArray(MODE_COUNT) }

    // refresh cpu stats
    LaunchedEffect(Unit) {
        while (true) {
            cpuUsagePercent = measureCpuUsage()
            delay(500)
        }
    }
    // refresh temperature
    LaunchedEffect(Unit) {
        while (true) {
            thermalTempC = readBatteryTemp(context)
            delay(2000)
        }
    }

    // handle frames as they arrive
    DisposableEffect(resW, resH) {
        imageReader.setOnImageAvailableListener({ reader ->
            if (isSwitchingRes) {
                reader.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener

            // filter out wrong frame sizes
            if (image.width != resW || image.height != resH) {
                image.close()
                return@setOnImageAvailableListener
            }

            try {
                // start frame timer
                val frameArrivalNs = System.nanoTime()
                val frameArrivalWallMs = System.currentTimeMillis()
                frameTimestamps.addLast(frameArrivalWallMs)
                if (frameTimestamps.size > 30) frameTimestamps.removeFirst()
                val fps = if (frameTimestamps.size >= 2) {
                    val spanMs = frameTimestamps.last() - frameTimestamps.first()
                    if (spanMs > 0) (frameTimestamps.size - 1) * 1000.0 / spanMs else 0.0
                } else 0.0

                // check delay between frames
                val hwTimestampNs = image.timestamp
                val intervalMs = if (lastHwTimestampNs[0] > 0L)
                    (hwTimestampNs - lastHwTimestampNs[0]) / 1_000_000L else -1L
                lastHwTimestampNs[0] = hwTimestampNs

                // pull data from image planes
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

                // convert data to colors
                val convStartNs = System.nanoTime()
                val rgbaBytes = activity.nativeYuvToRgba(
                    yBytes, uBytes, vBytes,
                    image.width, image.height,
                    yPlane.rowStride, uPlane.rowStride, uPlane.pixelStride
                )
                val convNsVal = System.nanoTime() - convStartNs

                if (rgbaBytes.isEmpty()) return@setOnImageAvailableListener

                // verify neon once
                if (!verifyOnce[0]) {
                    verifyOnce[0] = true
                    val result = activity.nativeVerifySobelCorrectness(rgbaBytes, image.width, image.height)
                    mainHandler.post { simdCheckResult = result }
                }

                // start gpu if needed
                if (!gpuInitDone[0]) {
                    gpuInitDone[0] = true
                    val initMsg = activity.nativeInitGpu()
                    val verifyMsg = if (initMsg.startsWith("GPU OK"))
                        activity.nativeVerifyGpuPassThrough(rgbaBytes, image.width, image.height)
                    else
                        initMsg
                    mainHandler.post { gpuInitResult = verifyMsg }

                    if (verifyMsg.startsWith("GPU PASS")) {
                        // test gpu variants
                        val benchMsg = activity.nativeBenchmarkGpuVariants(rgbaBytes, image.width, image.height)
                        mainHandler.post { gpuBenchResult = benchMsg }

                        // verify gpu sobel
                        val sobelVerify = activity.nativeVerifyGpuSobel(rgbaBytes, image.width, image.height)
                        mainHandler.post { gpuSobelCheckResult = sobelVerify }
                    }
                }

                // pick current mode
                var currentMode = mode
                if ((currentMode == 2 || currentMode == 3) && !gpuInitDone[0]) {
                    currentMode = 0
                }

                val oldMode = lastModeRef[0]
                val modeChanged = currentMode != oldMode
                lastModeRef[0] = currentMode
                if (modeChanged) {
                    modeWarmupFrames[currentMode] = SPEEDUP_WARMUP_FRAMES
                }

                // run selected filter
                val sobelStartNs = System.nanoTime()
                val edgeBytes = when (currentMode) {
                    1    -> activity.nativeSobelNeon(rgbaBytes, image.width, image.height)
                    2    -> activity.nativeGpuSobel(rgbaBytes, image.width, image.height)
                    3    -> activity.nativeHybridSobel(rgbaBytes, image.width, image.height)
                    4    -> rgbaBytes
                    else -> activity.nativeSobelFilter(rgbaBytes, image.width, image.height)
                }
                val sobelNsVal = System.nanoTime() - sobelStartNs

                if (edgeBytes.isEmpty()) return@setOnImageAvailableListener

                // get hybrid mode stats
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

                // create bitmap for display
                val bmpCreateStartNs = System.nanoTime()
                rawBmp.copyPixelsFromBuffer(ByteBuffer.wrap(edgeBytes))
                val bmpCreateNsVal = System.nanoTime() - bmpCreateStartNs

                // rotate bitmap for screen
                val bmpRotateStartNs = System.nanoTime()
                val backIdx = backIdxRef[0]
                rotCanvases[backIdx].drawBitmap(rawBmp, rotMatrix, null)
                backIdxRef[0] = 1 - backIdx
                val bmpRotateNsVal = System.nanoTime() - bmpRotateStartNs

                // total frame time
                val e2eNsVal = System.nanoTime() - frameArrivalNs

                // check for timing jumps
                val e2eMs = e2eNsVal / 1_000_000.0
                val avg = rollingE2eMs[0]
                val isJitter = avg > 0.0 && e2eMs > avg * 2.0
                rollingE2eMs[0] = if (avg == 0.0) e2eMs else avg * 0.9 + e2eMs * 0.1

                val droppedOnSwitch = modeChanged && intervalMs > 40L

                // send data to ui
                mainHandler.post {
                    currentFps      = fps
                    yuvExtractNs    = yuvExtractNsVal
                    conversionNs    = convNsVal
                    sobelNs         = sobelNsVal
                    bitmapCreateNs  = bmpCreateNsVal
                    bitmapRotateNs  = bmpRotateNsVal
                    endToEndNs      = e2eNsVal
                    frameIntervalMs = intervalMs
                    processedBitmap = rotBitmaps[backIdx]
                    
                    // compute averages for speedup
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
                    
                    // update split info for hybrid
                    if (haveHybridStats) {
                        hybridMidRow     = hybridMidRowSnap
                        hybridNeonHalfNs = hybridNeonSnap
                        hybridGpuHalfNs  = hybridGpuSnap
                    }
                }
            } finally {
                // free frame data
                image.close()
            }
        }, backgroundHandler)

        onDispose {
            imageReader.setOnImageAvailableListener(null, null)
        }
    }

    // restart camera sequence
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

    // reset stats on resize
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

    // clean up camera resources
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
        
        // camera view component
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

        // show frame on screen
        processedBitmap?.let { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }

        // color for current mode
        val accent = modeAccent(mode)

        // dashboard layout
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 50.dp, start = 12.dp, end = 12.dp, bottom = 12.dp)
                .clip(CardShape)
                .background(Color.Black.copy(alpha = 0.55f))
                .height(IntrinsicSize.Min)
        ) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(accent))

            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                // fps and mode display
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
                    // toggle more info
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

                // total time info
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

                // processing time
                SectionHeader("PIPELINE")
                MetricRow("Sobel", fmtNs(sobelNs), Color.Cyan, bold = true)

                // speed gains
                if (baselineSamples > 0 && (neonSamples > 0 || gpuSamples > 0 || hybridSamples > 0)) {
                    SectionHeader("SPEEDUP")
                    MetricRow("Base", fmtNs(baselineSobelNs), Color.Cyan)
                    if (neonSamples > 0)
                        MetricRow("NEON", fmtNs(neonSobelNs), Color.Green)
                    if (gpuSamples > 0)
                        MetricRow("GPU ", fmtNs(gpuSobelNs), GpuOrange)
                    if (hybridSamples > 0) {
                        MetricRow("Hyb ", fmtNs(hybridSobelNs), Color.Magenta)
                        // hybrid row split
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

                // system health info
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

                }
            }
        }

        // resolution toggle
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 50.dp, end = 12.dp, start = 12.dp, bottom = 12.dp)
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

        // mode selection bar
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 28.dp)
                .clip(PillShape)
                .background(Color.Black.copy(alpha = 0.6f))
        ) {
            ModePill("raw",  4, mode) { mode = 4 }
            ModePill("base", 0, mode) { mode = 0 }
            ModePill("simd", 1, mode) { mode = 1 }
            ModePill("gpu",  2, mode) { mode = 2 }
            ModePill("hyb",  3, mode) { mode = 3 }
        }
    }
}

// format time into readable ms
fun fmtNs(ns: Long): String = "${String.format("%.2f", ns / 1_000_000.0)} ms"

// dashboard style settings
private val CardShape  = RoundedCornerShape(10.dp)
private val PillShape  = RoundedCornerShape(20.dp)
private val BadgeShape = RoundedCornerShape(4.dp)
private val LabelGray  = Color(0xFFB0B0B0)
private val DimGray    = Color(0xFF9E9E9E)
private val GpuOrange  = Color(0xFFFF9800)
private val PassGreen  = Color(0xFF1B5E20)
private val FailRed    = Color(0xFFB71C1C)

// get color for current mode
private fun modeAccent(mode: Int): Color = when (mode) {
    0    -> Color.Cyan
    1    -> Color.Green
    2    -> GpuOrange
    3    -> Color.Magenta
    else -> Color.White
}

// get name for current mode
private fun modeName(mode: Int): String = when (mode) {
    0    -> "BASELINE"
    1    -> "SIMD"
    2    -> "GPU"
    3    -> "HYBRID"
    else -> "RAW"
}

// dashboard section header
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

// dashboard metric row
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

// status pill for ui
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

// mode button pill
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

// read system cpu usage
suspend fun measureCpuUsage(): Double {
    fun readStats(): LongArray = try {
        val line = File("/proc/stat").readLines().firstOrNull() ?: return LongArray(8)
        line.trim().split("\\s+".toRegex()).drop(1).take(8).map { it.toLong() }.toLongArray()
    } catch (e: Exception) { LongArray(8) }

    val s1 = readStats()
    delay(500)
    val s2 = readStats()

    val totalDelta = s2.sum() - s1.sum()
    val idleDelta  = (s2[3] + s2[4]) - (s1[3] + s1[4])
    return if (totalDelta > 0) (totalDelta - idleDelta) * 100.0 / totalDelta else 0.0
}

// read battery temperature
fun readBatteryTemp(context: Context): Double {
    val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val raw = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
    return if (raw > 0) raw / 10.0 else -1.0
}

// open camera and setup session
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

    // find rear camera id
    val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
        cameraManager.getCameraCharacteristics(id)
            .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
    } ?: run { Log.e(TAG, "no rear camera found"); return }

    // pick best fps range
    val characteristics    = cameraManager.getCameraCharacteristics(cameraId)
    val availableFpsRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
    val targetFpsRange: Range<Int>? = availableFpsRanges?.firstOrNull { it.lower == 30 && it.upper == 30 }
                          ?: availableFpsRanges?.maxByOrNull { it.upper }
    Log.d(TAG, "fps ranges: ${availableFpsRanges?.toList()} -> selected: $targetFpsRange")

    surfaceTexture.setDefaultBufferSize(resW, resH)
    val previewSurface = Surface(surfaceTexture)

    // open camera device
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

            // create capture session
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
