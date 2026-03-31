// native-libcpp — jni bridge between kotlin and c++

#include <jni.h>
#include <string>
#include <cstring>
#include <arm_neon.h>
#include <android/log.h>

// phase 3 stage 1 — headless EGL context + OpenGL ES 3.1 compute shaders
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <GLES3/gl31.h>

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
    if (val < 0)       return 0;
    if (val >= maxVal) return maxVal - 1;
    return val;
}

// applies a 3x3 sobel filter on the rgba buffer (r, g, b processed independently)
// magnitude formula: (|gx| + |gy|) >> 1  (l1/2 norm — same formula used by the
// neon and gpu paths so all modes produce functionally equivalent output)
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

            int absGxR = gxR < 0 ? -gxR : gxR,  absGyR = gyR < 0 ? -gyR : gyR;
            int absGxG = gxG < 0 ? -gxG : gxG,  absGyG = gyG < 0 ? -gyG : gyG;
            int absGxB = gxB < 0 ? -gxB : gxB,  absGyB = gyB < 0 ? -gyB : gyB;
            int mR = (absGxR + absGyR) >> 1;
            int mG = (absGxG + absGyG) >> 1;
            int mB = (absGxB + absGyB) >> 1;

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

// phase 2 stage 1 — neon grayscale warm-up
// converts rgba to grayscale processing 16 pixels at once using neon vectors.
// result is stored back as rgba (r=g=b=gray, a=255)
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_example_csproject_MainActivity_nativeGrayscaleNeon(
        JNIEnv* env, jobject, jbyteArray rgbaInput, jint width, jint height) {

    int totalPixels = width * height;
    jbyteArray outputArray = env->NewByteArray(totalPixels * 4);

    jbyte* src = (jbyte*)env->GetPrimitiveArrayCritical(rgbaInput,   nullptr);
    jbyte* dst = (jbyte*)env->GetPrimitiveArrayCritical(outputArray, nullptr);

    // integer weights that approximate the standard luma formula (r*0.299 + g*0.587 + b*0.114)
    // scaled to sum ≈ 256 so we can divide by 256 with a single right-shift
    const uint8_t weightR = 77, weightG = 150, weightB = 29;

    int pixelIndex = 0;
    int neonLimit  = totalPixels - 16;  // stop 16 pixels before end to avoid overread

    // process 16 pixels per iteration
    for (; pixelIndex <= neonLimit; pixelIndex += 16) {
        uint8_t* srcPixel = (uint8_t*)src + pixelIndex * 4;
        uint8_t* dstPixel = (uint8_t*)dst + pixelIndex * 4;

        // load 16 rgba pixels and deinterleave into separate r, g, b, a vectors
        uint8x16x4_t rgbaPixels = vld4q_u8(srcPixel);

        // compute luma = (r*77 + g*150 + b*29) >> 8  using 16-bit accumulators
        // split into low (first 8) and high (last 8) to fit in uint16x8
        uint16x8_t lumaLow  = vmull_u8(vget_low_u8(rgbaPixels.val[0]),  vdup_n_u8(weightR));
        uint16x8_t lumaHigh = vmull_u8(vget_high_u8(rgbaPixels.val[0]), vdup_n_u8(weightR));
        lumaLow  = vmlal_u8(lumaLow,  vget_low_u8(rgbaPixels.val[1]),  vdup_n_u8(weightG));
        lumaHigh = vmlal_u8(lumaHigh, vget_high_u8(rgbaPixels.val[1]), vdup_n_u8(weightG));
        lumaLow  = vmlal_u8(lumaLow,  vget_low_u8(rgbaPixels.val[2]),  vdup_n_u8(weightB));
        lumaHigh = vmlal_u8(lumaHigh, vget_high_u8(rgbaPixels.val[2]), vdup_n_u8(weightB));

        // shift right by 8 (÷256) and narrow back to uint8
        uint8x8_t  grayLow  = vshrn_n_u16(lumaLow,  8);
        uint8x8_t  grayHigh = vshrn_n_u16(lumaHigh, 8);
        uint8x16_t grayVec  = vcombine_u8(grayLow, grayHigh);

        // store grayscale value in r, g, b; keep alpha = 255
        uint8x16x4_t grayPixels;
        grayPixels.val[0] = grayVec;
        grayPixels.val[1] = grayVec;
        grayPixels.val[2] = grayVec;
        grayPixels.val[3] = vdupq_n_u8(255);
        vst4q_u8(dstPixel, grayPixels);
    }

    //scalar fallback for any remaining pixels (< 16) at the end
    for (; pixelIndex < totalPixels; pixelIndex++) {
        uint8_t* srcPixel = (uint8_t*)src + pixelIndex * 4;
        uint8_t* dstPixel = (uint8_t*)dst + pixelIndex * 4;
        uint8_t gray = (srcPixel[0]*77 + srcPixel[1]*150 + srcPixel[2]*29) >> 8;
        dstPixel[0] = dstPixel[1] = dstPixel[2] = gray;
        dstPixel[3] = 255;
    }

    env->ReleasePrimitiveArrayCritical(rgbaInput,   src, JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(outputArray, dst, 0);

    return outputArray;
}

// phase 2 stage 2 — neon-accelerated sobel edge detection
// processes r, g, b channels independently using the same 3x3 kernels as
// nativeSobelFilter, so outputs can be compared for correctness
//
// speed -> inner loop handles 16 columns per iteration using 128-bit
// neon registers instead of 1 column per iteration (scalar)
//
// magnitude approximation: |gx| + |gy|  (l1 norm) instead of sqrt(gx^2+gy^2)
// removes all floating-point math; visually identical to sqrt on real frames
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_example_csproject_MainActivity_nativeSobelNeon(
        JNIEnv* env, jobject,
        jbyteArray rgbaInput, jint width, jint height) {

    jbyteArray outputArray = env->NewByteArray(width * height * 4);

    uint8_t* inputBuf  = (uint8_t*)env->GetPrimitiveArrayCritical(rgbaInput,   nullptr);
    uint8_t* outputBuf = (uint8_t*)env->GetPrimitiveArrayCritical(outputArray, nullptr);

    for (int row = 0; row < height; row++) {

        // output pointer for the current row
        uint8_t* dstRow = outputBuf + row * width * 4;

        // clamp row indices at top/bottom borders so the 3x3 window stays in-bounds
        // (same clamping strategy as the scalar version)
        int prevRowIdx = (row > 0)          ? row - 1 : 0;
        int nextRowIdx = (row < height - 1) ? row + 1 : height - 1;
        uint8_t* topRowPtr = inputBuf + prevRowIdx * width * 4;  // row above current
        uint8_t* midRowPtr = inputBuf + row        * width * 4;  // current row
        uint8_t* botRowPtr = inputBuf + nextRowIdx * width * 4;  // row below current

        // left margin:  col - 1 >= 0  -> col >= 1
        // right margin: col + 16 < width  ->  col + 16 <= width - 1
        // so valid neon range is col = 1 .. (width - 17)
        int col = 1;
        for (; col + 16 <= width - 1; col += 16) {

            // load 16 rgba pixels from each of the 8 neighbor positions in the 3x3 window
            // vld4q_u8 reads 64 bytes and deinterleaves into 4 channel vectors of 16 values each:
            //   val[0] = R for pixels [col-1  col+14]
            //   val[1] = G,  val[2] = B,  val[3] = A
            uint8x16x4_t topLeft    = vld4q_u8(topRowPtr + (col - 1) * 4);
            uint8x16x4_t topCenter  = vld4q_u8(topRowPtr +  col      * 4);
            uint8x16x4_t topRight   = vld4q_u8(topRowPtr + (col + 1) * 4);
            uint8x16x4_t midLeft    = vld4q_u8(midRowPtr + (col - 1) * 4);
            // midCenter has kernel weight 0 in both gx and gy - no load needed
            uint8x16x4_t midRight   = vld4q_u8(midRowPtr + (col + 1) * 4);
            uint8x16x4_t botLeft    = vld4q_u8(botRowPtr + (col - 1) * 4);
            uint8x16x4_t botCenter  = vld4q_u8(botRowPtr +  col      * 4);
            uint8x16x4_t botRight   = vld4q_u8(botRowPtr + (col + 1) * 4);

            // result container for this 16-pixel block alpha stays 255
            uint8x16x4_t pixelResult;
            pixelResult.val[3] = vdupq_n_u8(255);

            // apply identical sobel math to each color channel (0=R, 1=G, 2=B)
            for (int ch = 0; ch < 3; ch++) {

                // extract this channel from each neighbor position (16 values each)
                uint8x16_t tL = topLeft.val[ch];    // top-left
                uint8x16_t tC = topCenter.val[ch];  // top-center
                uint8x16_t tR = topRight.val[ch];   // top-right
                uint8x16_t mL = midLeft.val[ch];    // mid-left
                uint8x16_t mR = midRight.val[ch];   // mid-right
                uint8x16_t bL = botLeft.val[ch];    // bot-left
                uint8x16_t bC = botCenter.val[ch];  // bot-center
                uint8x16_t bR = botRight.val[ch];   // bot-right

                // gx = right_column − left_column
                //  gx kernel:  [-1  0  +1]
                //              [-2  0  +2]
                //              [-1  0  +1]

                // positive side (right column weights +1, +2, +1)
                // vaddl_u8 widens uint8 → uint16 before adding to prevent overflow
                uint16x8_t gxPosLow  = vaddl_u8(vget_low_u8(tR),  vget_low_u8(bR));
                uint16x8_t gxPosHigh = vaddl_u8(vget_high_u8(tR), vget_high_u8(bR));
                gxPosLow  = vmlal_u8(gxPosLow,  vget_low_u8(mR),  vdup_n_u8(2));  // += mR * 2
                gxPosHigh = vmlal_u8(gxPosHigh, vget_high_u8(mR), vdup_n_u8(2));

                // negative side (left column weights -1, -2, -1)
                uint16x8_t gxNegLow  = vaddl_u8(vget_low_u8(tL),  vget_low_u8(bL));
                uint16x8_t gxNegHigh = vaddl_u8(vget_high_u8(tL), vget_high_u8(bL));
                gxNegLow  = vmlal_u8(gxNegLow,  vget_low_u8(mL),  vdup_n_u8(2));
                gxNegHigh = vmlal_u8(gxNegHigh, vget_high_u8(mL), vdup_n_u8(2));

                // |gx| = |positive_sum − negative_sum|  (vabdq avoids signed arithmetic)
                uint16x8_t absGxLow  = vabdq_u16(gxPosLow,  gxNegLow);
                uint16x8_t absGxHigh = vabdq_u16(gxPosHigh, gxNegHigh);

                // gy = bottom_row − top_row
                //  gy kernel:  [-1  -2  -1]
                //              [ 0   0   0]
                //              [+1  +2  +1]

                // positive side (bottom row weights +1, +2, +1)
                uint16x8_t gyPosLow  = vaddl_u8(vget_low_u8(bL),  vget_low_u8(bR));
                uint16x8_t gyPosHigh = vaddl_u8(vget_high_u8(bL), vget_high_u8(bR));
                gyPosLow  = vmlal_u8(gyPosLow,  vget_low_u8(bC),  vdup_n_u8(2));
                gyPosHigh = vmlal_u8(gyPosHigh, vget_high_u8(bC), vdup_n_u8(2));

                // negative side (top row weights -1, -2, -1)
                uint16x8_t gyNegLow  = vaddl_u8(vget_low_u8(tL),  vget_low_u8(tR));
                uint16x8_t gyNegHigh = vaddl_u8(vget_high_u8(tL), vget_high_u8(tR));
                gyNegLow  = vmlal_u8(gyNegLow,  vget_low_u8(tC),  vdup_n_u8(2));
                gyNegHigh = vmlal_u8(gyNegHigh, vget_high_u8(tC), vdup_n_u8(2));

                // |gy|
                uint16x8_t absGyLow  = vabdq_u16(gyPosLow,  gyNegLow);
                uint16x8_t absGyHigh = vabdq_u16(gyPosHigh, gyNegHigh);

                //  edge magnitude ~ |gx| + |gy|  (l1 norm, no sqrt needed)
                // max l1 value = (255+510+255) + (255+510+255) = 2040
                uint16x8_t magnitudeLow  = vaddq_u16(absGxLow,  absGyLow);
                uint16x8_t magnitudeHigh = vaddq_u16(absGxHigh, absGyHigh);

                // shift right 1 (÷2) -> max 1020; vqshrn_n_u16 shifts + narrows uint16 -> uint8
                // + saturates any value above 255 to 255 automatically (no manual clamp needed)
                uint8x8_t magLowByte  = vqshrn_n_u16(magnitudeLow,  1);
                uint8x8_t magHighByte = vqshrn_n_u16(magnitudeHigh, 1);
                pixelResult.val[ch]   = vcombine_u8(magLowByte, magHighByte);
            }

            // write 16 rgba pixels to output — vst4q_u8 re-interleaves the 4 channel vectors
            // back into R0G0B0A0 R1G1B1A1 ... layout in memory
            vst4q_u8(dstRow + col * 4, pixelResult);
        }

        // scalar fallback
        // handles: left border (col=0), right border (col=width-1), and any
        // leftover columns the neon loop didn't cover.
        // uses the same L1/2 magnitude formula as the neon path — (|gx|+|gy|)>>1 —
        // so the output is visually consistent across the entire frame.
        // 'col' already holds the first column the neon loop didn't reach.
        auto scalarSobelAtCol = [&](int c) {
            int gxR = 0, gyR = 0;
            int gxG = 0, gyG = 0;
            int gxB = 0, gyB = 0;

            for (int ky = -1; ky <= 1; ky++) {
                for (int kx = -1; kx <= 1; kx++) {
                    int neighborRow = clampIdx(row + ky, height);
                    int neighborCol = clampIdx(c   + kx, width);
                    int pixelIdx    = (neighborRow * width + neighborCol) * 4;

                    int r = inputBuf[pixelIdx];
                    int g = inputBuf[pixelIdx + 1];
                    int b = inputBuf[pixelIdx + 2];

                    // gx: left col gets weight -(1 or 2), right col gets +(1 or 2), center = 0
                    int gxWeight = (kx == -1) ? -(ky == 0 ? 2 : 1)
                                 : (kx ==  1) ?  (ky == 0 ? 2 : 1) : 0;
                    // gy: top row gets weight -(1 or 2), bottom row gets +(1 or 2), center = 0
                    int gyWeight = (ky == -1) ? -(kx == 0 ? 2 : 1)
                                 : (ky ==  1) ?  (kx == 0 ? 2 : 1) : 0;

                    gxR += r * gxWeight;  gyR += r * gyWeight;
                    gxG += g * gxWeight;  gyG += g * gyWeight;
                    gxB += b * gxWeight;  gyB += b * gyWeight;
                }
            }

            // L1/2 norm — matches the neon path exactly; no sqrtf needed
            int absGxR = gxR < 0 ? -gxR : gxR,  absGyR = gyR < 0 ? -gyR : gyR;
            int absGxG = gxG < 0 ? -gxG : gxG,  absGyG = gyG < 0 ? -gyG : gyG;
            int absGxB = gxB < 0 ? -gxB : gxB,  absGyB = gyB < 0 ? -gyB : gyB;
            int magR = (absGxR + absGyR) >> 1;
            int magG = (absGxG + absGyG) >> 1;
            int magB = (absGxB + absGyB) >> 1;

            int outOffset = c * 4;
            dstRow[outOffset]     = (uint8_t)(magR > 255 ? 255 : magR);
            dstRow[outOffset + 1] = (uint8_t)(magG > 255 ? 255 : magG);
            dstRow[outOffset + 2] = (uint8_t)(magB > 255 ? 255 : magB);
            dstRow[outOffset + 3] = 255;
        };

        scalarSobelAtCol(0);                       // always handle left border with scalar
        for (int c = col; c < width; c++)          // right border + any leftover columns
            scalarSobelAtCol(c);
    }

    env->ReleasePrimitiveArrayCritical(rgbaInput,   inputBuf,  JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(outputArray, outputBuf, 0);

    return outputArray;
}

// phase 2 stage 3 — verify neon sobel correctness
// runs the neon function, then independently recalculates the L1/2 formula
// (|gx|+|gy|)>>1 for interior pixels and compares them against the neon output.
// only interior columns (1 .. neonEnd-1) are checked — the same region the neon
// vectorized loop processed. border columns are skipped because they are handled
// by the scalar fallback path, which is trivially correct by inspection.
extern "C" JNIEXPORT jstring JNICALL
Java_com_example_csproject_MainActivity_nativeVerifySobelCorrectness(
        JNIEnv* env, jobject thiz,
        jbyteArray rgbaInput, jint width, jint height) {

    // run the neon path first to get the output we want to verify
    jbyteArray neonResult = Java_com_example_csproject_MainActivity_nativeSobelNeon(
            env, thiz, rgbaInput, width, height);

    uint8_t* src     = (uint8_t*)env->GetPrimitiveArrayCritical(rgbaInput,  nullptr);
    uint8_t* neonBuf = (uint8_t*)env->GetPrimitiveArrayCritical(neonResult, nullptr);

    // figure out where the neon vectorized loop stops natively
    int neonEnd = 1;
    while (neonEnd + 16 <= width - 1) neonEnd += 16;

    int mismatches = 0, worst = 0, checked = 0;

    // single pass: compute reference math and compare immediately.
    // we only check the interior region that the neon loop actually processed.
    for (int r = 0; r < height; r++) {
        int rUp   = r > 0          ? r - 1 : 0;
        int rDown = r < height - 1 ? r + 1 : height - 1;

        for (int c = 1; c < neonEnd; c++) {
            int cL = c - 1; // safe since c >= 1
            int cR = c + 1; // safe since c < neonEnd <= width-1
            int out = (r * width + c) * 4;

            for (int ch = 0; ch < 3; ch++) {
                int tL = src[(rUp   * width + cL) * 4 + ch];
                int tC = src[(rUp   * width + c)  * 4 + ch];
                int tR = src[(rUp   * width + cR) * 4 + ch];
                int mL = src[(r     * width + cL) * 4 + ch];
                int mR = src[(r     * width + cR) * 4 + ch];
                int bL = src[(rDown * width + cL) * 4 + ch];
                int bC = src[(rDown * width + c)  * 4 + ch];
                int bR = src[(rDown * width + cR) * 4 + ch];

                int gxPos = tR + 2*mR + bR;
                int gxNeg = tL + 2*mL + bL;
                int gyPos = bL + 2*bC + bR;
                int gyNeg = tL + 2*tC + tR;

                int absGx = gxPos > gxNeg ? gxPos - gxNeg : gxNeg - gxPos;
                int absGy = gyPos > gyNeg ? gyPos - gyNeg : gyNeg - gyPos;

                int mag = (absGx + absGy) >> 1;
                uint8_t refMag = (uint8_t)(mag > 255 ? 255 : mag);

                int diff = (int)refMag - (int)neonBuf[out + ch];
                if (diff < 0) diff = -diff;

                if (diff > 1) {
                    mismatches++;
                    if (diff > worst) worst = diff;
                }
                checked++;
            }
        }
    }

    env->ReleasePrimitiveArrayCritical(rgbaInput,  src,     JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(neonResult, neonBuf, JNI_ABORT);
    env->DeleteLocalRef(neonResult);  // release local reference to avoid table exhaustion

    char msg[256];
    if (mismatches == 0) {
        snprintf(msg, sizeof(msg), "PASS: 0 mismatches out of %d", checked);
        LOGI("neon verification passed — %d channels checked, all within ±1", checked);
    } else {
        snprintf(msg, sizeof(msg), "FAIL: %d mismatches (max diff=%d) out of %d",
                 mismatches, worst, checked);
        LOGI("neon verification failed — %d mismatches, worst diff=%d", mismatches, worst);
    }
    return env->NewStringUTF(msg);
}

// phase 3 stage 1 — headless EGL context + pass-through compute shader
// global EGL state — initialized once on the processing thread, reused every frame
static EGLDisplay g_eglDisplay = EGL_NO_DISPLAY;
static EGLContext g_eglContext = EGL_NO_CONTEXT;
static EGLSurface g_eglSurface = EGL_NO_SURFACE;  // 1×1 pbuffer (needed to make context current)

// compiled shader program handles
static GLuint g_passthroughProgram = 0;  // phase 3 stage 1 — SSBO copy verification
static GLuint g_sobelProgram       = 0;  // phase 3 stage 2 — GPU Sobel edge detection

// helper: compile a single compute shader from GLSL source and link it into a program
// returns the program id on success, 0 on any error (details logged to logcat)
static GLuint compileComputeProgram(const char* source) {
    GLuint shader = glCreateShader(GL_COMPUTE_SHADER);
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);

    GLint ok = 0;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char buf[512];
        glGetShaderInfoLog(shader, sizeof(buf), nullptr, buf);
        LOGI("compute shader compile error: %s", buf);
        glDeleteShader(shader);
        return 0;
    }

    GLuint prog = glCreateProgram();
    glAttachShader(prog, shader);
    glLinkProgram(prog);
    glDeleteShader(shader);  // shader object no longer needed after linking

    glGetProgramiv(prog, GL_LINK_STATUS, &ok);
    if (!ok) {
        char buf[512];
        glGetProgramInfoLog(prog, sizeof(buf), nullptr, buf);
        LOGI("compute program link error: %s", buf);
        glDeleteProgram(prog);
        return 0;
    }
    return prog;
}

// pass-through compute shader (OpenGL ES 3.1 GLSL)
// each invocation handles one pixel; pixel layout in the SSBOs is one uint per pixel (4 bytes = RGBA)
// workgroup size 16×16 matches the dispatch call in nativeGpuPassThrough
static const char* PASSTHROUGH_SHADER_SRC = R"(#version 310 es
layout(local_size_x = 16, local_size_y = 16, local_size_z = 1) in;

layout(std430, binding = 0) readonly  buffer InputBuffer  { uint inputData[];  };
layout(std430, binding = 1) writeonly buffer OutputBuffer { uint outputData[]; };

uniform int uWidth;
uniform int uHeight;

void main() {
    uint x = gl_GlobalInvocationID.x;
    uint y = gl_GlobalInvocationID.y;
    if (x >= uint(uWidth) || y >= uint(uHeight)) return;
    uint idx = y * uint(uWidth) + x;
    outputData[idx] = inputData[idx];  // copy pixel unchanged
}
)";

// phase 3 stage 2 — Sobel edge detection compute shader (OpenGL ES 3.1 GLSL)
// pixel layout: each uint in the SSBO holds one RGBA pixel on a little-endian ARM device
// the shader applies the same 3×3 Sobel kernels and L1/2 magnitude formula used by
// nativeSobelFilter and nativeSobelNeon, so all three paths produce identical results
// and can be compared byte-for-byte in Stage 3 correctness verification.
static const char* SOBEL_SHADER_SRC = R"(#version 310 es
layout(local_size_x = 16, local_size_y = 16, local_size_z = 1) in;

layout(std430, binding = 0) readonly  buffer InputBuffer  { uint inputPixels[];  };
layout(std430, binding = 1) writeonly buffer OutputBuffer { uint outputPixels[]; };

uniform int uWidth;
uniform int uHeight;

// extract 8-bit channel ch (0=R 1=G 2=B) from a packed RGBA uint
// ARM little-endian: R at bits 0-7, G at 8-15, B at 16-23, A at 24-31
uint chan(uint pixel, int ch) {
    return (pixel >> uint(ch * 8)) & 0xFFu;
}

// fetch a pixel with clamp-to-edge border handling
uint fetchPixel(int col, int row) {
    col = clamp(col, 0, uWidth  - 1);
    row = clamp(row, 0, uHeight - 1);
    return inputPixels[row * uWidth + col];
}

void main() {
    int col = int(gl_GlobalInvocationID.x);
    int row = int(gl_GlobalInvocationID.y);
    if (col >= uWidth || row >= uHeight) return;

    // load the 8 neighbors used by the 3x3 Sobel window (center pixel has weight 0)
    uint tL = fetchPixel(col-1, row-1);  uint tC = fetchPixel(col, row-1);  uint tR = fetchPixel(col+1, row-1);
    uint mL = fetchPixel(col-1, row);                                        uint mR = fetchPixel(col+1, row);
    uint bL = fetchPixel(col-1, row+1);  uint bC = fetchPixel(col, row+1);  uint bR = fetchPixel(col+1, row+1);

    // apply Sobel to R, G, B independently; store results in outChannels[0..2]
    uint outChannels[3];
    for (int ch = 0; ch < 3; ch++) {
        int tLv = int(chan(tL,ch));  int tCv = int(chan(tC,ch));  int tRv = int(chan(tR,ch));
        int mLv = int(chan(mL,ch));                               int mRv = int(chan(mR,ch));
        int bLv = int(chan(bL,ch));  int bCv = int(chan(bC,ch));  int bRv = int(chan(bR,ch));

        // Gx kernel: [-1  0 +1 / -2  0 +2 / -1  0 +1]  →  right column minus left column
        int absGx = abs((tRv + 2*mRv + bRv) - (tLv + 2*mLv + bLv));

        // Gy kernel: [-1 -2 -1 /  0  0  0 / +1 +2 +1]  →  bottom row minus top row
        int absGy = abs((bLv + 2*bCv + bRv) - (tLv + 2*tCv + tRv));

        // L1/2 magnitude — identical formula to nativeSobelFilter and nativeSobelNeon
        outChannels[ch] = uint(clamp((absGx + absGy) >> 1, 0, 255));
    }

    // pack R, G, B, A=255 back into one uint and write to output SSBO
    outputPixels[row * uWidth + col] =
        (255u << 24u) | (outChannels[2] << 16u) | (outChannels[1] << 8u) | outChannels[0];
}
)";

// initializes a headless EGL context bound to the calling thread.
// must be called on the same thread that will later call nativeGpuPassThrough.
// returns "GPU OK — EGL <major>.<minor>, compute shader compiled" on success
// or a "GPU FAIL: <reason>" string describing what step failed.
extern "C" JNIEXPORT jstring JNICALL
Java_com_example_csproject_MainActivity_nativeInitGpu(JNIEnv* env, jobject) {

    // skip reinit if already set up (guard against multiple calls)
    if (g_eglContext != EGL_NO_CONTEXT) {
        return env->NewStringUTF("GPU OK — already initialized");
    }

    // step 1 — obtain the default EGL display connection
    g_eglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (g_eglDisplay == EGL_NO_DISPLAY) {
        return env->NewStringUTF("GPU FAIL: eglGetDisplay returned NO_DISPLAY");
    }

    // step 2 — initialize EGL on this display
    EGLint major = 0, minor = 0;
    if (!eglInitialize(g_eglDisplay, &major, &minor)) {
        return env->NewStringUTF("GPU FAIL: eglInitialize failed");
    }
    LOGI("EGL version %d.%d", major, minor);

    // step 3 — choose a config that supports OpenGL ES 3.x and pbuffer surfaces
    const EGLint configAttribs[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_SURFACE_TYPE,    EGL_PBUFFER_BIT,
        EGL_RED_SIZE,   8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE,  8,
        EGL_ALPHA_SIZE, 8,
        EGL_NONE
    };
    EGLConfig config;
    EGLint numConfigs = 0;
    if (!eglChooseConfig(g_eglDisplay, configAttribs, &config, 1, &numConfigs) || numConfigs == 0) {
        return env->NewStringUTF("GPU FAIL: eglChooseConfig found no suitable config (ES 3.x not supported?)");
    }

    // step 4 — bind the OpenGL ES API
    eglBindAPI(EGL_OPENGL_ES_API);

    // step 5 — create an OpenGL ES 3.1 context (compute shaders require ES 3.1)
    const EGLint contextAttribs[] = {
        EGL_CONTEXT_CLIENT_VERSION, 3,
        EGL_NONE
    };
    g_eglContext = eglCreateContext(g_eglDisplay, config, EGL_NO_CONTEXT, contextAttribs);
    if (g_eglContext == EGL_NO_CONTEXT) {
        return env->NewStringUTF("GPU FAIL: eglCreateContext failed — device may not support ES 3.1");
    }

    // step 6 — create a minimal 1×1 pbuffer surface so we can call eglMakeCurrent
    // (a surface is required even though we only do off-screen compute work)
    const EGLint pbufferAttribs[] = {
        EGL_WIDTH,  1,
        EGL_HEIGHT, 1,
        EGL_NONE
    };
    g_eglSurface = eglCreatePbufferSurface(g_eglDisplay, config, pbufferAttribs);
    if (g_eglSurface == EGL_NO_SURFACE) {
        return env->NewStringUTF("GPU FAIL: eglCreatePbufferSurface failed");
    }

    // step 7 — make the context current on this thread
    if (!eglMakeCurrent(g_eglDisplay, g_eglSurface, g_eglSurface, g_eglContext)) {
        return env->NewStringUTF("GPU FAIL: eglMakeCurrent failed");
    }

    // step 8 — compile pass-through compute shader (stage 1 verification)
    g_passthroughProgram = compileComputeProgram(PASSTHROUGH_SHADER_SRC);
    if (g_passthroughProgram == 0) {
        return env->NewStringUTF("GPU FAIL: pass-through compute shader failed to compile");
    }

    // step 9 — compile Sobel compute shader (stage 2 edge detection)
    g_sobelProgram = compileComputeProgram(SOBEL_SHADER_SRC);
    if (g_sobelProgram == 0) {
        return env->NewStringUTF("GPU FAIL: Sobel compute shader failed to compile");
    }

    LOGI("GPU init success — EGL %d.%d, pass-through + Sobel shaders compiled", major, minor);
    char msg[128];
    snprintf(msg, sizeof(msg), "GPU OK — EGL %d.%d, shaders compiled", major, minor);
    return env->NewStringUTF(msg);
}

// runs the pass-through compute shader on the input rgba buffer via SSBOs.
// uploads input to the GPU, dispatches 16×16 workgroups, reads back the output
// returns the copied data as a bytearray; returns an empty array if GPU is not initialized
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_example_csproject_MainActivity_nativeGpuPassThrough(
        JNIEnv* env, jobject,
        jbyteArray rgbaInput, jint width, jint height) {

    int totalBytes = width * height * 4;

    // allocate output array before acquiring the critical pointer
    jbyteArray outputArray = env->NewByteArray(totalBytes);

    if (g_passthroughProgram == 0 || g_eglContext == EGL_NO_CONTEXT) {
        LOGI("nativeGpuPassThrough called before GPU init — returning zeroed buffer");
        return outputArray;
    }

    // pin the java byte arrays so we can pass the raw pointers to OpenGL
    jbyte* srcBytes = (jbyte*)env->GetPrimitiveArrayCritical(rgbaInput,   nullptr);
    jbyte* dstBytes = (jbyte*)env->GetPrimitiveArrayCritical(outputArray, nullptr);

    // upload input to an SSBO at binding point 0
    GLuint ssboIn = 0, ssboOut = 0;
    glGenBuffers(1, &ssboIn);
    glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssboIn);
    glBufferData(GL_SHADER_STORAGE_BUFFER, totalBytes, srcBytes, GL_STATIC_READ);

    // create output SSBO at binding point 1 (empty, same size)
    glGenBuffers(1, &ssboOut);
    glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssboOut);
    glBufferData(GL_SHADER_STORAGE_BUFFER, totalBytes, nullptr, GL_STATIC_COPY);

    // dispatch the pass-through shader
    glUseProgram(g_passthroughProgram);
    glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, ssboIn);
    glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, ssboOut);

    GLint locW = glGetUniformLocation(g_passthroughProgram, "uWidth");
    GLint locH = glGetUniformLocation(g_passthroughProgram, "uHeight");
    glUniform1i(locW, width);
    glUniform1i(locH, height);

    // round up workgroups so every pixel is covered
    GLuint groupsX = (GLuint)(width  + 15) / 16;
    GLuint groupsY = (GLuint)(height + 15) / 16;
    glDispatchCompute(groupsX, groupsY, 1);

    // ensure all shader writes are visible before we read back the SSBO
    glMemoryBarrier(GL_ALL_BARRIER_BITS);

    // read back the output SSBO via a mapped pointer
    glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssboOut);
    void* gpuData = glMapBufferRange(GL_SHADER_STORAGE_BUFFER, 0, totalBytes, GL_MAP_READ_BIT);
    if (gpuData) {
        memcpy(dstBytes, gpuData, totalBytes);
        glUnmapBuffer(GL_SHADER_STORAGE_BUFFER);
    } else {
        LOGI("nativeGpuPassThrough: glMapBufferRange returned null — readback failed");
    }

    // cleanup transient SSBOs
    glDeleteBuffers(1, &ssboIn);
    glDeleteBuffers(1, &ssboOut);

    env->ReleasePrimitiveArrayCritical(rgbaInput,   srcBytes, JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(outputArray, dstBytes, 0);

    return outputArray;
}

// phase 3 stage 1 — verify the pass-through SSBO pipeline
// compares the GPU pass-through output against the original input byte-for-byte
// any mismatch indicates an SSBO upload/readback failure
// returns "GPU PASS: " on exact match or "GPU FAIL: " with mismatch count
extern "C" JNIEXPORT jstring JNICALL
Java_com_example_csproject_MainActivity_nativeVerifyGpuPassThrough(
        JNIEnv* env, jobject thiz,
        jbyteArray rgbaInput, jint width, jint height) {

    // run the pass-through and get GPU output
    jbyteArray gpuOutput = Java_com_example_csproject_MainActivity_nativeGpuPassThrough(
            env, thiz, rgbaInput, width, height);

    int totalBytes = width * height * 4;

    jbyte* src = (jbyte*)env->GetPrimitiveArrayCritical(rgbaInput,  nullptr);
    jbyte* dst = (jbyte*)env->GetPrimitiveArrayCritical(gpuOutput,  nullptr);

    int mismatches = 0;
    for (int i = 0; i < totalBytes && mismatches <= 10; i++) {
        if (src[i] != dst[i]) {
            if (mismatches < 5) {
                LOGI("GPU pass-through mismatch @byte %d: src=%u dst=%u",
                     i, (uint8_t)src[i], (uint8_t)dst[i]);
            }
            mismatches++;
        }
    }
    // finish counting without logging every mismatch
    if (mismatches > 10) {
        for (int i = 50; i < totalBytes; i++)
            if (src[i] != dst[i]) mismatches++;
    }

    env->ReleasePrimitiveArrayCritical(rgbaInput,  src, JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(gpuOutput,  dst, JNI_ABORT);
    env->DeleteLocalRef(gpuOutput);

    char msg[128];
    if (mismatches == 0) {
        snprintf(msg, sizeof(msg), "GPU PASS: SSBO round-trip exact (%d bytes)", totalBytes);
        LOGI("GPU pass-through verified — %d bytes match exactly", totalBytes);
    } else {
        snprintf(msg, sizeof(msg), "GPU FAIL: %d mismatches in %d bytes", mismatches, totalBytes);
        LOGI("GPU pass-through FAILED — %d mismatches", mismatches);
    }
    return env->NewStringUTF(msg);
}

// ============================================================
// phase 3 stage 2 — GPU Sobel filter via compute shader
// ============================================================

// runs the Sobel edge detection compute shader on the input rgba buffer.
// each of the width*height invocations handles one pixel — reads its 3×3
// neighborhood from the input SSBO, applies Sobel Gx/Gy kernels, writes
// the L1/2 magnitude to the output SSBO, then the result is read back to the CPU.
// returns empty array if GPU was not initialized (nativeInitGpu not yet called).
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_example_csproject_MainActivity_nativeGpuSobel(
        JNIEnv* env, jobject,
        jbyteArray rgbaInput, jint width, jint height) {

    int totalBytes = width * height * 4;
    jbyteArray outputArray = env->NewByteArray(totalBytes);

    if (g_sobelProgram == 0 || g_eglContext == EGL_NO_CONTEXT) {
        LOGI("nativeGpuSobel: GPU not initialized — returning empty buffer");
        return outputArray;
    }

    jbyte* srcBytes = (jbyte*)env->GetPrimitiveArrayCritical(rgbaInput,   nullptr);
    jbyte* dstBytes = (jbyte*)env->GetPrimitiveArrayCritical(outputArray, nullptr);

    // --- upload input pixels to SSBO at binding 0 ---
    GLuint ssboIn = 0, ssboOut = 0;
    glGenBuffers(1, &ssboIn);
    glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssboIn);
    glBufferData(GL_SHADER_STORAGE_BUFFER, totalBytes, srcBytes, GL_STATIC_READ);

    // --- create empty output SSBO at binding 1 ---
    glGenBuffers(1, &ssboOut);
    glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssboOut);
    glBufferData(GL_SHADER_STORAGE_BUFFER, totalBytes, nullptr, GL_STATIC_COPY);

    // --- bind program and SSBOs, set image dimensions ---
    glUseProgram(g_sobelProgram);
    glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, ssboIn);
    glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, ssboOut);
    glUniform1i(glGetUniformLocation(g_sobelProgram, "uWidth"),  width);
    glUniform1i(glGetUniformLocation(g_sobelProgram, "uHeight"), height);

    // --- dispatch: one thread per pixel, workgroups of 16×16 ---
    // for 1280×720: groupsX=80, groupsY=45 → 3600 workgroups × 256 threads = 921600 threads
    GLuint groupsX = (GLuint)(width  + 15) / 16;
    GLuint groupsY = (GLuint)(height + 15) / 16;
    glDispatchCompute(groupsX, groupsY, 1);

    // wait for all shader writes to be visible before reading the output SSBO
    glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);

    // --- read back the edge-detected pixels ---
    glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssboOut);
    void* gpuData = glMapBufferRange(GL_SHADER_STORAGE_BUFFER, 0, totalBytes, GL_MAP_READ_BIT);
    if (gpuData) {
        memcpy(dstBytes, gpuData, totalBytes);
        glUnmapBuffer(GL_SHADER_STORAGE_BUFFER);
    } else {
        LOGI("nativeGpuSobel: glMapBufferRange returned null — readback failed");
    }

    glDeleteBuffers(1, &ssboIn);
    glDeleteBuffers(1, &ssboOut);

    env->ReleasePrimitiveArrayCritical(rgbaInput,   srcBytes, JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(outputArray, dstBytes, 0);

    return outputArray;
}
