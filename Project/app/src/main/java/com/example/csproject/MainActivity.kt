package com.example.csproject

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState

class MainActivity : ComponentActivity() {

    //declares kotlin function whose body is implemented in c++
    external fun nativeGetStatus(): String

    companion object {
        init {
            //loads libcsproject.so from the APK into memory when the class is first used
            //"csproject" must match the library name in CMakeLists.txt add_library("csproject" ...)
            System.loadLibrary("csproject")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

            //calls the JNI function once at startup to verify the native bridge works and native..() crosses from kotin into c++ execute the native code
            // and return the string is ok or not
        android.util.Log.d("CSProject", nativeGetStatus())

        setContent {
            CameraApp()
        }
    }
}

    //API is still marked experimental so we must explicitly opt in to suppress the warning
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraApp() {
    //rememberPermissionState tracks the status of a single runtime permission
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)

    if (cameraPermissionState.status.isGranted) {
        //permission has been granted by the user to show the live camera feed
        CameraScreen()
    } else {
        //permission has not been granted yet
        //so it will not access the camera hardware at all until permission is granted
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("Camera permission is required.")
            Spacer(modifier = Modifier.height(8.dp))
            //launchPermissionRequest() triggers the system permission dialog after the user taps Allow or Deny cameraPermissionState.status updates
            Button(onClick = { cameraPermissionState.launchPermissionRequest() }) {
                Text("Request Permission")
            }
        }
    }
}

@Composable
fun CameraScreen() {
    //localContext.current gives us the Android Context for this composable
    val context = LocalContext.current

    //localLifecycleOwner.current gives us the lifecycle of the current screen we pass this to CameraX so the camera automatically starts when the screen
    //becomes visible and stops when the screen is backgrounded or destroyed preventing battery drain and camera resource leaks

    val lifecycleOwner = LocalLifecycleOwner.current

    //mutableStateOf(0.0) creates an observable value When currentFps changes compose automatically redraws only the text widget that reads it
    // remember keeps this state alive across recompositions of CameraScreen
    var currentFps by remember { mutableStateOf(0.0) }

    //box stacks its children on top of each other we use this to layer the camera preview underneath the FPS overlay
    Box(modifier = Modifier.fillMaxSize()) {

        //androidView embeds a classic Android View inside Compose PreviewView is a CameraX View that renders the live camera feed
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                //previewView is the surface that CameraX draws the camera frames onto
                val previewView = PreviewView(ctx)

                //ProcessCameraProvider binds the camera to the app process
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                // addListener runs our lambda when the camera provider is ready getMainExecutor ensures the callback runs on the main ie. UI thread
                //which is required for any UI-touching camera operations.
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()

                    //preview is a CameraX use case that streams frames to a Surface setSurfaceProvider connects it to previewView so the live feed
                    //is rendered directly onto the screen without any extra copies.
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    //ImageAnalysis is a CameraX use case that delivers each camera
                    val imageAnalyzer = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    //Records the timestamp of the previous frame to compute FPS
                    var lastFrameTime = System.currentTimeMillis()

                    //setAnalyzer registers our per-frame callback.
                    imageAnalyzer.setAnalyzer(ContextCompat.getMainExecutor(ctx)) { imageProxy ->
                        val currentTime = System.currentTimeMillis()

                        //deltaTime=milliseconds elapsed since the last frame arrived
                        val deltaTime = currentTime - lastFrameTime

                        if (deltaTime > 0) {
                            //FPS=how many frames fit in one second
                            currentFps = 1000.0 / deltaTime
                        }

                        lastFrameTime = currentTime

                        //imageProxy.close() must be called after every frame cameraX uses a fixed pool of image buffers If we never
                        //release the buffer the pool runs dry and the camera stops delivering new frames entirely
                        imageProxy.close()
                    }

                    //cameraSelector tells CameraX which physical camera to open DEFAULT_BACK_CAMERA = the rear-facing camera.
                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                    try {
                        //unbindAll-> releases any previously active camera use cases
                        cameraProvider.unbindAll()

                        //bindToLifecycle -> activates both use cases on the selected camer
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            imageAnalyzer
                        )
                    } catch (exc: Exception) {
                        exc.printStackTrace()
                    }
                }, ContextCompat.getMainExecutor(ctx))

                //factory must return the View that AndroidView will display
                //returning previewView puts the live camera feed on screen
                previewView
            }
        )

        // Semi-transparent HUD overlay displayed on top of the camera feed
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(8.dp)
        ) {
            // Displays the live FPS value, formatted to 1 decimal place
            Text(
                text = "FPS: ${String.format("%.1f", currentFps)}",
                color = Color.White,
                fontSize = 18.sp
            )
        }
    }
}
