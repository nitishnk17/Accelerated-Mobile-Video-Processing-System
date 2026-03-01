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
