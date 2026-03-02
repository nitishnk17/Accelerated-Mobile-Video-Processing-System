// native-lib.cpp — jni bridge between kotlin and c++

#include <jni.h>
#include <string>
#include <android/log.h>

#define LOG_TAG "CSProject"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// called from kotlin at startup to verify the ndk toolchain is working
extern "C" JNIEXPORT jstring JNICALL
Java_com_example_csproject_MainActivity_nativeGetStatus(JNIEnv* env, jobject /* this */) {
    LOGI("nativeGetStatus called — jni bridge is working");
    return env->NewStringUTF("JNI OK — NDK toolchain ready");
}

// converts yuv_420_888 planes to rgba using bt.601 scalar conversion
// stores result in a new jbyteArray and returns it to kotlin
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_example_csproject_MainActivity_nativeYuvToRgba(
        JNIEnv* env, jobject,
        jbyteArray yArray, jbyteArray uArray, jbyteArray vArray,
        jint width, jint height,
        jint yRowStride, jint uvRowStride, jint uvPixelStride) {

    jbyte* y = env->GetByteArrayElements(yArray, nullptr);
    jbyte* u = env->GetByteArrayElements(uArray, nullptr);
    jbyte* v = env->GetByteArrayElements(vArray, nullptr);

    jbyteArray rgbaArray = env->NewByteArray(width * height * 4);
    jbyte* rgba = env->GetByteArrayElements(rgbaArray, nullptr);

    for (int row = 0; row < height; row++) {
        for (int col = 0; col < width; col++) {
            int yIdx  = row * yRowStride + col;
            int uvIdx = (row / 2) * uvRowStride + (col / 2) * uvPixelStride;

            int yVal = (uint8_t)y[yIdx];
            int uVal = (uint8_t)u[uvIdx];
            int vVal = (uint8_t)v[uvIdx];

            // bt.601 conversion
            int yp = yVal - 16;
            int cb = uVal - 128;
            int cr = vVal - 128;

            int r = (298 * yp + 409 * cr + 128) >> 8;
            int g = (298 * yp - 100 * cb - 208 * cr + 128) >> 8;
            int b = (298 * yp + 516 * cb + 128) >> 8;

            r = r < 0 ? 0 : r > 255 ? 255 : r;
            g = g < 0 ? 0 : g > 255 ? 255 : g;
            b = b < 0 ? 0 : b > 255 ? 255 : b;

            int out = (row * width + col) * 4;
            rgba[out]     = (jbyte)r;
            rgba[out + 1] = (jbyte)g;
            rgba[out + 2] = (jbyte)b;
            rgba[out + 3] = (jbyte)255;
        }
    }

    env->ReleaseByteArrayElements(yArray, y, JNI_ABORT);
    env->ReleaseByteArrayElements(uArray, u, JNI_ABORT);
    env->ReleaseByteArrayElements(vArray, v, JNI_ABORT);
    env->ReleaseByteArrayElements(rgbaArray, rgba, 0);

    return rgbaArray;
}
