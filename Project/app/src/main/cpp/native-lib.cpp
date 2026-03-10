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
    if (val < 0)       return 0;
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

            // load 16 rgba pixels from each of the 8 neighbor positions in the 3x3 window.
            // vld4q_u8 reads 64 bytes and deinterleaves into 4 channel vectors of 16 values each:
            //   .val[0] = R for pixels [col-1 .. col+14]
            //   .val[1] = G,  .val[2] = B,  .val[3] = A
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
        // uses the exact same 3x3 kernel + clampIdx as nativeSobelFilter so
        // outputs are comparable during stage 3 correctness verification.
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

            int magR = (int)sqrtf((float)(gxR*gxR + gyR*gyR));
            int magG = (int)sqrtf((float)(gxG*gxG + gyG*gyG));
            int magB = (int)sqrtf((float)(gxB*gxB + gyB*gyB));

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

// phase 2 stage 3 — verify neon sobel against a scalar reference
// runs the neon function, then independently calculates the L1/2 sobel formula
// on-the-fly for interior pixels and compares them. we skip the border columns
// because the neon implementation uses a different fallback formula (sqrtf) there.
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
