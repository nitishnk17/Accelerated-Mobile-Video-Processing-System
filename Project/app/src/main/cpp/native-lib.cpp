// native-lib.cpp — jni bridge between kotlin and c++

#include <jni.h>
#include <string>
#include <cstdint>
#include <algorithm>
#include <android/log.h>

#define LOG_TAG "CSProject"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// clamp an integer to [0, 255] — used after yuv→rgb math
static inline uint8_t clamp8(int v) {
    return static_cast<uint8_t>(std::max(0, std::min(255, v)));
}

// called from kotlin at startup to verify the ndk toolchain is working
extern "C" JNIEXPORT jstring JNICALL
Java_com_example_csproject_MainActivity_nativeGetStatus(JNIEnv* env, jobject /* this */) {
    LOGI("nativeGetStatus called — jni bridge is working");
    return env->NewStringUTF("JNI OK — NDK toolchain ready");
}

// converts a yuv_420_888 frame to rgba in-place using bt.601
// yuv_420_888 layout:
//   Y plane  — one byte per pixel, full resolution (w × h)
//   U plane  — one byte per 2×2 block, subsampled
//   V plane  — one byte per 2×2 block, subsampled

// bt.601 conversion (fixed-point, shift by 10 to avoid floats):
//   R = Y + 1.370705 * (V-128)  →  Y + (1404*(V-128)) >> 10
//   G = Y - 0.337633 * (U-128) - 0.698001 * (V-128)  →  Y - (346*(U-128) + 715*(V-128)) >> 10
//   B = Y + 1.732446 * (U-128)  →  Y + (1774*(U-128)) >> 10

extern "C" JNIEXPORT void JNICALL
Java_com_example_csproject_MainActivity_nativeYuvToRgba(
        JNIEnv* env, jobject,
        jobject yBuffer, jobject uBuffer, jobject vBuffer,
        jint width, jint height,
        // strides-rows can be padded beyond image width, and uv pixels may be
        // interleaved (pixelStride=2, e.g. NV21) or planar (pixelStride=1)
        jint yRowStride, jint uvRowStride, jint uvPixelStride,
        jobject rgbaOut) {

    // get direct buffer addresses for zero-copy access from Java to C++
    auto* y   = static_cast<uint8_t*>(env->GetDirectBufferAddress(yBuffer));
    auto* u   = static_cast<uint8_t*>(env->GetDirectBufferAddress(uBuffer));
    auto* v   = static_cast<uint8_t*>(env->GetDirectBufferAddress(vBuffer));
    auto* out = static_cast<uint8_t*>(env->GetDirectBufferAddress(rgbaOut));

    for (int row = 0; row < height; row++) {
        // hoist row-dependent pointers out of the inner loop
        const uint8_t* yRow  = y + row * yRowStride;
        const uint8_t* uvRow = u + (row >> 1) * uvRowStride;
        const uint8_t* vRow  = v + (row >> 1) * uvRowStride;
        uint8_t* outRow = out + row * width * 4;

        for (int col = 0; col < width; col++) {
            int yVal = yRow[col] & 0xFF;

            // u and v are subsampled — each sample covers a 2×2 pixel block
            int uvIdx = (col >> 1) * uvPixelStride;
            int uVal = (uvRow[uvIdx] & 0xFF) - 128;
            int vVal = (vRow[uvIdx] & 0xFF) - 128;

            // bt.601 fixed-point conversion (×1024 then >>10)
            int r = yVal + ((1404 * vVal) >> 10);
            int g = yVal - ((346 * uVal + 715 * vVal) >> 10);
            int b = yVal + ((1774 * uVal) >> 10);

            outRow[0] = clamp8(r);
            outRow[1] = clamp8(g);
            outRow[2] = clamp8(b);
            outRow[3] = 0xFF;  // alpha — fully opaque
            outRow += 4;
        }
    }
}
