# Phase 1 — Stage 1: Project Setup and Toolchain

**Goal:** Enable the NDK so native C/C++ code can be compiled via JNI and called from Kotlin.
Everything else in Stage 1 (API 29+, camera permission, version control, build cycle) is already done.

---

## What you need to do (4 steps)

---

## Step 1 — Edit `Project/app/build.gradle.kts`

### Where to add (inside `defaultConfig { }` block, after the existing lines):

```kotlin
defaultConfig {
    applicationId = "com.example.csproject"
    minSdk = 29
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    // ADD THIS — tells NDK which CPU architectures to build for
    // arm64-v8a  = all modern Android phones (64-bit ARM)
    // x86_64     = emulator support
    ndk {
        abiFilters += listOf("arm64-v8a", "x86_64")
    }

    // ADD THIS — passes C++17 flag to the CMake compiler
    externalNativeBuild {
        cmake {
            cppFlags += "-std=c++17"
        }
    }
}
```

### Where to add (inside `android { }` block, after `buildTypes { }` block):

```kotlin
// ADD THIS — tells Gradle where to find the CMakeLists.txt file
externalNativeBuild {
    cmake {
        path = "src/main/cpp/CMakeLists.txt"
        version = "3.22.1"
    }
}
```

### What this does:
- `ndk { abiFilters }` — limits the build to 64-bit ARM (real phones) and x86_64 (emulator). Without this, Gradle tries to build for every architecture and can fail.
- `externalNativeBuild { cmake { path } }` — points Gradle to your CMakeLists.txt so it knows there is native C++ code to compile.
- `cppFlags += "-std=c++17"` — enables C++17 features in your C++ files (needed later for NEON and shader code).

---

## Step 2 — Create new file: `Project/app/src/main/cpp/CMakeLists.txt`

Create the folder `app/src/main/cpp/` and inside it create `CMakeLists.txt` with this content:

```cmake
# Minimum CMake version required by Android NDK
cmake_minimum_required(VERSION 3.22.1)

# Name of this project (can be anything)
project("csproject")

# Defines a shared native library (.so file) that Android will load at runtime
# SHARED = compiled as a .so (required for JNI on Android)
# native-lib.cpp = the C++ source file to compile
add_library(
    csproject
    SHARED
    native-lib.cpp
)

# Find the Android system log library (used for __android_log_print)
find_library(log-lib log)

# Link our library against the system log library
target_link_libraries(
    csproject
    ${log-lib}
)
```

### What this does:
- `add_library(csproject SHARED native-lib.cpp)` — compiles `native-lib.cpp` into a shared library called `libcsproject.so`. This is the file that gets bundled in the APK and loaded by `System.loadLibrary("csproject")` in Kotlin.
- `find_library(log-lib log)` + `target_link_libraries` — links the Android system log library so you can use `__android_log_print` in C++ for debugging (like `Log.d` but from native code).

---

## Step 3 — Create new file: `Project/app/src/main/cpp/native-lib.cpp`

In the same `app/src/main/cpp/` folder, create `native-lib.cpp`:

```cpp
#include <jni.h>
#include <string>
#include <android/log.h>

#define LOG_TAG "CSProject"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// This is the JNI function Kotlin will call.
// Naming rule: Java_<package_with_underscores>_<ClassName>_<methodName>
// Package: com.example.csproject  →  com_example_csproject
// Class:   MainActivity
// Method:  nativeGetStatus
extern "C" JNIEXPORT jstring JNICALL
Java_com_example_csproject_MainActivity_nativeGetStatus(JNIEnv* env, jobject /* this */) {
    LOGI("nativeGetStatus called — JNI bridge is working");
    return env->NewStringUTF("JNI OK — NDK toolchain ready");
}
```

### What this does:
- `extern "C"` — prevents C++ name mangling so JNI can find the function by name.
- `JNIEXPORT` / `JNICALL` — required JNI calling convention markers.
- `jstring` — the return type. A JNI string that maps to Kotlin's `String`.
- `JNIEnv* env` — the JNI environment pointer. You use this to create Java/Kotlin objects from C++ (like `env->NewStringUTF(...)`).
- `LOGI(...)` — logs to Android logcat under the tag `CSProject`. You can see this in Android Studio's Logcat.
- The function just returns a string `"JNI OK — NDK toolchain ready"` to confirm the bridge works.

---

## Step 4 — Edit `Project/app/src/main/java/com/example/csproject/MainActivity.kt`

### Change 1 — Add `companion object` inside `MainActivity` to load the native library:

Find this in `MainActivity.kt`:
```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CameraApp()
        }
    }
}
```

Replace it with:
```kotlin
class MainActivity : ComponentActivity() {

    // Declares the external JNI function.
    // Kotlin sees this as a normal function; the implementation is in native-lib.cpp.
    external fun nativeGetStatus(): String

    companion object {
        init {
            // Loads libcsproject.so from the APK at app startup.
            // Must match the library name in CMakeLists.txt add_library("csproject" ...)
            System.loadLibrary("csproject")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Call the JNI function once at startup to verify the toolchain.
        // You will see "JNI OK — NDK toolchain ready" in Android Studio Logcat.
        android.util.Log.d("CSProject", nativeGetStatus())

        setContent {
            CameraApp()
        }
    }
}
```

### What this does:
- `external fun nativeGetStatus(): String` — declares a Kotlin function whose body lives in C++. The `external` keyword tells the compiler "implementation is in a loaded native library".
- `System.loadLibrary("csproject")` — loads `libcsproject.so` from the APK. Must be called before any `external` function is invoked. Putting it in `companion object { init { } }` guarantees it runs when the class is first loaded.
- `android.util.Log.d("CSProject", nativeGetStatus())` — calls across the JNI bridge and logs the result. If you see `JNI OK — NDK toolchain ready` in Logcat, Stage 1 is complete.

---

## How to verify Stage 1 is complete

1. Sync Gradle (Android Studio will prompt you, or click "Sync Now")
2. Build the project — should compile with no errors
3. Run on a physical device
4. Open **Logcat** in Android Studio, filter by tag `CSProject`
5. You should see:
   ```
   D/CSProject: JNI OK — NDK toolchain ready
   ```

If you see that log line, the NDK toolchain is working and Phase 1 Stage 1 is done.

---

## Final file structure after Stage 1

```
Project/
└── app/
    ├── build.gradle.kts              ← modified (NDK + CMake config added)
    └── src/
        └── main/
            ├── cpp/                  ← NEW folder
            │   ├── CMakeLists.txt    ← NEW file
            │   └── native-lib.cpp   ← NEW file
            ├── java/com/example/csproject/
            │   └── MainActivity.kt  ← modified (loadLibrary + external fun + log)
            └── AndroidManifest.xml  ← no change needed
```

---

## What comes next (Stage 2)

Stage 2 replaces the current CameraX preview with a proper **Camera2 API** pipeline that:
- Uses `CameraManager` to open the rear camera
- Configures an `ImageReader` for `YUV_420_888` frames at `1280×720` @ 30 FPS on a background thread
- Logs frame timestamps to verify steady delivery

The JNI bridge you built in Stage 1 will be used in Stage 3 to receive those YUV frames in C++ and convert them to RGBA.
