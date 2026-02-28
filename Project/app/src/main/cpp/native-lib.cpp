// native-lib.cpp — JNI Bridge 
//JNI (Java Native Interface) is the bridge that lets Kotlin/Java call C++ functions and vice versa

//this file currently contains one function whose only job is to confirm that the NDK toolchain is set up correctly

//jni.h — provides JNI types and macros
#include <jni.h>

#include <string>

// android/log.h — provides __android_log_print() which writes to Android Logcat
#include <android/log.h>

//LOG_TAG is the tag string that appears in the Logcat Tag column
#define LOG_TAG "CSProject"

//LOGI is a convenience macro for logging at INFO level with our chosen LOG_TAG it takes printf-style arguments and forwards them to __android_log_print()
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

//JNI FUNCTION: nativeGetStatus
extern "C" JNIEXPORT jstring JNICALL
Java_com_example_csproject_MainActivity_nativeGetStatus(JNIEnv* env, jobject /* this */) {

    //write a message to Android Logcat to confirm we reached native code Visible in Android Studio Logcat filtered by tag "CSProject"
    LOGI("nativeGetStatus called — JNI bridge is working");

    //env->NewStringUTF() converts a plain C string (const char*) into a JNI jstring that Kotlin can receive as a normal String
    //this string is returned to the Kotlin caller of nativeGetStatus()
    return env->NewStringUTF("JNI OK — NDK toolchain ready");
}
