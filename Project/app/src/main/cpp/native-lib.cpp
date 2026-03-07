// native-lib.cpp — jni bridge between kotlin and c++

#include <jni.h>
#include <string>
#include <cmath>
#include <arm_neon.h>
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

// helper — clamp an index so it stays inside the image bounds
static inline int clampIdx(int val, int maxVal) {
    if (val < 0)      return 0;
    if (val >= maxVal) return maxVal - 1;
    return val;
}

// applies a 3x3 sobel filter on the rgba buffer (r, g, b processed independently)
// produces bright edges on a dark background; alpha stays 255
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_example_csproject_MainActivity_nativeSobelFilter(
        JNIEnv* env, jobject,
        jbyteArray rgbaInput, jint width, jint height) {

    // allocate output before entering critical section (no jni calls allowed inside)
    int totalPixels = width * height;
    jbyteArray outArray = env->NewByteArray(totalPixels * 4);

    jbyte* src = (jbyte*)env->GetPrimitiveArrayCritical(rgbaInput, nullptr);
    jbyte* dst = (jbyte*)env->GetPrimitiveArrayCritical(outArray, nullptr);

    // sobel kernels — standard 3x3
    //  Gx:  -1  0  1     Gy:  -1 -2 -1
    //       -2  0  2           0  0  0
    //       -1  0  1           1  2  1
    const int gxKernel[3][3] = {{-1, 0, 1}, {-2, 0, 2}, {-1, 0, 1}};
    const int gyKernel[3][3] = {{-1, -2, -1}, {0, 0, 0}, {1, 2, 1}};

    for (int row = 0; row < height; row++) {
        for (int col = 0; col < width; col++) {
            int gxR = 0, gyR = 0;
            int gxG = 0, gyG = 0;
            int gxB = 0, gyB = 0;

            // convolve 3x3 neighborhood, read all channels per neighbor
            for (int ky = -1; ky <= 1; ky++) {
                for (int kx = -1; kx <= 1; kx++) {
                    int sr = clampIdx(row + ky, height);
                    int sc = clampIdx(col + kx, width);
                    int idx = (sr * width + sc) * 4;

                    int r = (uint8_t)src[idx];
                    int g = (uint8_t)src[idx + 1];
                    int b = (uint8_t)src[idx + 2];

                    int wx = gxKernel[ky + 1][kx + 1];
                    int wy = gyKernel[ky + 1][kx + 1];

                    gxR += r * wx;  gyR += r * wy;
                    gxG += g * wx;  gyG += g * wy;
                    gxB += b * wx;  gyB += b * wy;
                }
            }

            int mR = (int)sqrtf((float)(gxR * gxR + gyR * gyR));
            int mG = (int)sqrtf((float)(gxG * gxG + gyG * gyG));
            int mB = (int)sqrtf((float)(gxB * gxB + gyB * gyB));

            int out = (row * width + col) * 4;
            dst[out]     = (jbyte)(mR > 255 ? 255 : mR);
            dst[out + 1] = (jbyte)(mG > 255 ? 255 : mG);
            dst[out + 2] = (jbyte)(mB > 255 ? 255 : mB);
            dst[out + 3] = (jbyte)255;
        }
    }

    env->ReleasePrimitiveArrayCritical(rgbaInput, src, JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(outArray, dst, 0);

    return outArray;
}

// convert rgba to grayscale precoessing 16 pixels at once
// using vectors. result is grayscale stored back as rgba
extern "C" JNIEXPORT jbyteArray JNICALL
        Java_com_example_csproject_MainActivity_nativeGrayscaleNeon(
                JNIEnv* env, jobject, jbyteArray rgbaInput, jint width, jint height){

        int totalPixels=width*height;
        jbyteArray outputArray=env->NewByteArray(totalPixels*4);

        jbyte* src=(jbyte*)env->GetPrimitiveArrayCritical(rgbaInput, nullptr);
        jbyte* dst=(jbyte*)env->GetPrimitiveArrayCritical(outputArray, nullptr);

        //adding weight for faster calculation
        const uint8_t wR=77,wG=150, wB=29;

        int i=0;
        int limit=totalPixels-16;   //stop 16 pixels before end to avoid overread

        //process 16 pixels at a time
        for(;i<=limit;i+=16){
            uint8_t* s=(uint8_t*)src+i*4;
            uint8_t* d=(uint8_t*)dst+i*4;

            //deinterleave rgba into sepeate r,g,b, a vectors 16 values each
            uint8x16x4_t px = vld4q_u8(s);      // load 4 interleaved arrays of 16 uint8 values from address s

            //compute: (r*77 + g*150 + b*29) >> 8
            uint16x8_t lo = vmull_u8(vget_low_u8(px.val[0]),  vdup_n_u8(wR));
            uint16x8_t hi = vmull_u8(vget_high_u8(px.val[0]), vdup_n_u8(wR));
            lo = vmlal_u8(lo, vget_low_u8(px.val[1]),  vdup_n_u8(wG));
            hi = vmlal_u8(hi, vget_high_u8(px.val[1]), vdup_n_u8(wG));
            lo = vmlal_u8(lo, vget_low_u8(px.val[2]),  vdup_n_u8(wB));
            hi = vmlal_u8(hi, vget_high_u8(px.val[2]), vdup_n_u8(wB));

            // shift right by 8 and narrow back to uint8
            uint8x8_t grayLo = vshrn_n_u16(lo, 8);
            uint8x8_t grayHi = vshrn_n_u16(hi, 8);
            uint8x16_t gray  = vcombine_u8(grayLo, grayHi);

            // is vshrn_n_u16(lo, 8) — shift right by 8 (divides by 256) AND narrow from uint16 to uint8 in one instruction
            // store grayscale value in R, G, B; keep alpha = 255
            uint8x16x4_t out;
            out.val[0] = gray;
            out.val[1] = gray;
            out.val[2] = gray;
            out.val[3] = vdupq_n_u8(255);
            vst4q_u8(d, out);
        }

        // handle remaining pixels (< 16) with scalar fallback
        for (; i < totalPixels; i++) {
            uint8_t* s = (uint8_t*)src + i * 4;
            uint8_t* d = (uint8_t*)dst + i * 4;
            uint8_t gray = (s[0]*77 + s[1]*150 + s[2]*29) >> 8;
            d[0] = d[1] = d[2] = gray;
            d[3] = 255;
        }

        env->ReleasePrimitiveArrayCritical(rgbaInput, src, JNI_ABORT);
        env->ReleasePrimitiveArrayCritical(outputArray,  dst, 0);

        return outputArray;
}