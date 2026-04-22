//jni bridge between kotlin and c++

#include<jni.h>
#include<string>
#include<cstring>
#include<cstdio>
#include<thread>
#include<time.h>
#include<arm_neon.h>
#include<android/log.h>

//gpu headers for compute work(opengl es 3.1)
#include<EGL/egl.h>
#include<EGL/eglext.h>
#include<GLES3/gl3.h>
#include<GLES3/gl31.h>

#define LOG_TAG "CSProject"
#define LOGI(...)__android_log_print(ANDROID_LOG_INFO,LOG_TAG,__VA_ARGS__)

//verify if jni works at startup
extern "C" JNIEXPORT jstring JNICALL
Java_com_example_csproject_MainActivity_nativeGetStatus(JNIEnv* env,jobject /* this*/){
    LOGI("nativeGetStatus called");
    return env->NewStringUTF("jni ok");
}

//convert raw camera yuv data to rgba pixels
//camera frames come in three separate planes(y,u,v)
//we need to merge them and apply color math to get normal rgb
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_example_csproject_MainActivity_nativeYuvToRgba(
        JNIEnv* env,jobject,
        jbyteArray yArray,jbyteArray uArray,jbyteArray vArray,
        jint width,jint height,
        jint yRowStride,jint uvRowStride,jint uvPixelStride){

    jsize yLen=env->GetArrayLength(yArray);
    if(yLen < width* height){
        return env->NewByteArray(0);
    }

    //lock the java arrays so we can read them in c++
    jbyte* y=env->GetByteArrayElements(yArray,nullptr);
    jbyte* u=env->GetByteArrayElements(uArray,nullptr);
    jbyte* v=env->GetByteArrayElements(vArray,nullptr);

    //create a new array for the rgba pixels
    jbyteArray rgbaArray=env->NewByteArray(width* height* 4);
    jbyte* rgba=env->GetByteArrayElements(rgbaArray,nullptr);

    //scan every pixel and convert colors
    for(int row=0; row < height; row++){
        for(int col=0; col < width; col++){
            //find where the data is in the yuv planes
            //y is full size,u and v are half size(one for every 4 pixels)
            int yIdx=row* yRowStride+ col;
            int uvIdx=(row / 2)* uvRowStride+(col / 2)* uvPixelStride;

            int yVal=(uint8_t)y[yIdx];
            int uVal=(uint8_t)u[uvIdx];
            int vVal=(uint8_t)v[uvIdx];

            //standard math to turn yuv into red,green,and blue(bt.601)
            int yp=yVal - 16;
            int cb=uVal - 128;
            int cr=vVal - 128;

            int r=(298* yp+ 409* cr+ 128)>> 8;
            int g=(298* yp - 100* cb - 208* cr+ 128)>> 8;
            int b=(298* yp+ 516* cb+ 128)>> 8;

            //keep values between 0 and 255(clamp)
            r=r < 0 ? 0 : r > 255 ? 255 : r;
            g=g < 0 ? 0 : g > 255 ? 255 : g;
            b=b < 0 ? 0 : b > 255 ? 255 : b;

            //save to the output array as rgba
            int out=(row* width+ col)* 4;
            rgba[out]=(jbyte)r;
            rgba[out+ 1]=(jbyte)g;
            rgba[out+ 2]=(jbyte)b;
            rgba[out+ 3]=(jbyte)255;
        }
    }

    //unlock the arrays to avoid memory leaks
    env->ReleaseByteArrayElements(yArray,y,JNI_ABORT);
    env->ReleaseByteArrayElements(uArray,u,JNI_ABORT);
    env->ReleaseByteArrayElements(vArray,v,JNI_ABORT);
    env->ReleaseByteArrayElements(rgbaArray,rgba,0);

    return rgbaArray;
}

//helper to keep image indices safe(clamp to edge)
static inline int clampIdx(int val,int maxVal){
    if(val < 0)return 0;
    if(val >= maxVal)return maxVal - 1;
    return val;
}

//standard cpu sobel filter for edge detection
//this uses slow loops and processes each pixel one by one
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_example_csproject_MainActivity_nativeSobelFilter(
        JNIEnv* env,jobject,
        jbyteArray rgbaInput,jint width,jint height){

    jsize len=env->GetArrayLength(rgbaInput);
    if(len < width* height* 4)return env->NewByteArray(0);

    int totalPixels=width* height;
    jbyteArray outArray=env->NewByteArray(totalPixels* 4);

    //use critical access for faster reading from java heap
    jbyte* src=(jbyte*)env->GetPrimitiveArrayCritical(rgbaInput,nullptr);
    jbyte* dst=(jbyte*)env->GetPrimitiveArrayCritical(outArray,nullptr);

    //sobel kernels for horizontal(gx)and vertical(gy)detection
    const int gxKernel[3][3]={{-1,0,1},{-2,0,2},{-1,0,1}};
    const int gyKernel[3][3]={{-1,-2,-1},{0,0,0},{1,2,1}};

    //loop through every pixel in the frame
    for(int row=0; row < height; row++){
        for(int col=0; col < width; col++){
            int gxR=0,gyR=0;
            int gxG=0,gyG=0;
            int gxB=0,gyB=0;

            //check the 3x3 neighborhood around the current pixel
            for(int ky=-1; ky <= 1; ky++){
                for(int kx=-1; kx <= 1; kx++){
                    int sr=clampIdx(row+ ky,height);
                    int sc=clampIdx(col+ kx,width);
                    int idx=(sr* width+ sc)* 4;

                    int r=(uint8_t)src[idx];
                    int g=(uint8_t)src[idx+ 1];
                    int b=(uint8_t)src[idx+ 2];

                    int wx=gxKernel[ky+ 1][kx+ 1];
                    int wy=gyKernel[ky+ 1][kx+ 1];

                    //multiply pixel by kernel weights
                    gxR+= r* wx;  
                    gyR+= r* wy;
                    gxG+= g* wx;  
                    gyG+= g* wy;
                    gxB+= b* wx;  
                    gyB+= b* wy;
                }
            }

            //compute final magnitude of the edge(simple approximation)
            int absGxR=gxR < 0 ? -gxR : gxR,absGyR=gyR < 0 ? -gyR : gyR;
            int absGxG=gxG < 0 ? -gxG : gxG,absGyG=gyG < 0 ? -gyG : gyG;
            int absGxB=gxB < 0 ? -gxB : gxB,absGyB=gyB < 0 ? -gyB : gyB;
            int mR=(absGxR+ absGyR)>> 1;
            int mG=(absGxG+ absGyG)>> 1;
            int mB=(absGxB+ absGyB)>> 1;

            //write results to destination
            int out=(row* width+ col)* 4;
            dst[out]=(jbyte)(mR > 255 ? 255 : mR);
            dst[out+ 1]=(jbyte)(mG > 255 ? 255 : mG);
            dst[out+ 2]=(jbyte)(mB > 255 ? 255 : mB);
            dst[out+ 3]=(jbyte)255;
        }
    }

    //release buffers back to java
    env->ReleasePrimitiveArrayCritical(rgbaInput,src,JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(outArray,dst,0);

    return outArray;
}

//test grayscale using neon simd instructions
//this processes 16 pixels at once using wide registers
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_example_csproject_MainActivity_nativeGrayscaleNeon(
        JNIEnv* env,jobject,jbyteArray rgbaInput,jint width,jint height){

    int totalPixels=width* height;
    jbyteArray outputArray=env->NewByteArray(totalPixels* 4);

    jbyte* src=(jbyte*)env->GetPrimitiveArrayCritical(rgbaInput,nullptr);
    jbyte* dst=(jbyte*)env->GetPrimitiveArrayCritical(outputArray,nullptr);

    //fixed-point weights for grayscale
    const uint8_t weightR=77,weightG=150,weightB=29;

    int pixelIndex=0;
    int neonLimit=totalPixels - 16;

    //neon loop: handle 16 pixels per iteration in parallel
    for(; pixelIndex <= neonLimit; pixelIndex+= 16){
        uint8_t* srcPixel=(uint8_t*)src+ pixelIndex* 4;
        uint8_t* dstPixel=(uint8_t*)dst+ pixelIndex* 4;

        //load 16 rgba pixels(de-interleaved)into neon registers
        uint8x16x4_t rgbaPixels=vld4q_u8(srcPixel);

        //multiply each channel by its weight(widening to 16-bit to avoid overflow)
        uint16x8_t lumaLow=vmull_u8(vget_low_u8(rgbaPixels.val[0]),vdup_n_u8(weightR));
        uint16x8_t lumaHigh=vmull_u8(vget_high_u8(rgbaPixels.val[0]),vdup_n_u8(weightR));
        lumaLow=vmlal_u8(lumaLow,vget_low_u8(rgbaPixels.val[1]),vdup_n_u8(weightG));
        lumaHigh=vmlal_u8(lumaHigh,vget_high_u8(rgbaPixels.val[1]),vdup_n_u8(weightG));
        lumaLow=vmlal_u8(lumaLow,vget_low_u8(rgbaPixels.val[2]),vdup_n_u8(weightB));
        lumaHigh=vmlal_u8(lumaHigh,vget_high_u8(rgbaPixels.val[2]),vdup_n_u8(weightB));

        //shift result back down from 16-bit to 8-bit
        uint8x8_t  grayLow=vshrn_n_u16(lumaLow,8);
        uint8x8_t  grayHigh=vshrn_n_u16(lumaHigh,8);
        uint8x16_t grayVec=vcombine_u8(grayLow,grayHigh);

        //interleave and store results back to memory
        uint8x16x4_t grayPixels;
        grayPixels.val[0]=grayVec;
        grayPixels.val[1]=grayVec;
        grayPixels.val[2]=grayVec;
        grayPixels.val[3]=vdupq_n_u8(255);
        vst4q_u8(dstPixel,grayPixels);
    }

    //handle any leftover pixels that didn't fit in the 16-pixel block
    for(; pixelIndex < totalPixels; pixelIndex++){
        uint8_t* srcPixel=(uint8_t*)src+ pixelIndex* 4;
        uint8_t* dstPixel=(uint8_t*)dst+ pixelIndex* 4;
        uint8_t gray=(srcPixel[0]*77+ srcPixel[1]*150+ srcPixel[2]*29)>> 8;
        dstPixel[0]=dstPixel[1]=dstPixel[2]=gray;
        dstPixel[3]=255;
    }

    env->ReleasePrimitiveArrayCritical(rgbaInput,src,JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(outputArray,dst,0);

    return outputArray;
}

//sobel filter accelerated with arm neon
//this uses vector math to handle many pixels at once for high fps
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_example_csproject_MainActivity_nativeSobelNeon(
        JNIEnv* env,jobject,
        jbyteArray rgbaInput,jint width,jint height){

    jsize len=env->GetArrayLength(rgbaInput);
    if(len < width* height* 4)return env->NewByteArray(0);

    jbyteArray outputArray=env->NewByteArray(width* height* 4);

    uint8_t* inputBuf=(uint8_t*)env->GetPrimitiveArrayCritical(rgbaInput,nullptr);
    uint8_t* outputBuf=(uint8_t*)env->GetPrimitiveArrayCritical(outputArray,nullptr);

    //scan rows one by one
    for(int row=0; row < height; row++){

        uint8_t* dstRow=outputBuf+ row* width* 4;

        //pick neighboring rows for convolution
        int prevRowIdx=(row > 0)? row - 1 : 0;
        int nextRowIdx=(row < height - 1)? row+ 1 : height - 1;
        uint8_t* topRowPtr=inputBuf+ prevRowIdx* width* 4;
        uint8_t* midRowPtr=inputBuf+ row* width* 4;
        uint8_t* botRowPtr=inputBuf+ nextRowIdx* width* 4;

        //prefetch next row into cpu cache early to avoid waiting for ram
        if(row+ 2 < height){
            const uint8_t* prefetchRow=inputBuf+(row+ 2)* width* 4;
            for(int p=0; p < width* 4; p+= 64)
                __builtin_prefetch(prefetchRow+ p,0,1);
        }

        int col=1;
        //main neon loop: process a chunk of 16 pixels per iteration
        for(; col+ 16 <= width - 1; col+= 16){

            //hint cpu about data we will need soon
            __builtin_prefetch(topRowPtr+(col+ 32)* 4,0,2);
            __builtin_prefetch(midRowPtr+(col+ 32)* 4,0,2);
            __builtin_prefetch(botRowPtr+(col+ 32)* 4,0,2);
            __builtin_prefetch(dstRow+(col+ 32)* 4,1,1);

            //load 3x3 pixel neighborhoods into registers
            uint8x16x4_t topLeft=vld4q_u8(topRowPtr+(col - 1)* 4);
            uint8x16x4_t topCenter=vld4q_u8(topRowPtr+  col* 4);
            uint8x16x4_t topRight=vld4q_u8(topRowPtr+(col+ 1)* 4);
            uint8x16x4_t midLeft=vld4q_u8(midRowPtr+(col - 1)* 4);
            uint8x16x4_t midRight=vld4q_u8(midRowPtr+(col+ 1)* 4);
            uint8x16x4_t botLeft=vld4q_u8(botRowPtr+(col - 1)* 4);
            uint8x16x4_t botCenter=vld4q_u8(botRowPtr+  col* 4);
            uint8x16x4_t botRight=vld4q_u8(botRowPtr+(col+ 1)* 4);

            uint8x16x4_t pixelResult;
            pixelResult.val[3]=vdupq_n_u8(255); //set alpha to 100%

            //process red,green,and blue color channels
            for(int ch=0; ch < 3; ch++){

                uint8x16_t tL=topLeft.val[ch];
                uint8x16_t tC=topCenter.val[ch];
                uint8x16_t tR=topRight.val[ch];
                uint8x16_t mL=midLeft.val[ch];
                uint8x16_t mR=midRight.val[ch];
                uint8x16_t bL=botLeft.val[ch];
                uint8x16_t bC=botCenter.val[ch];
                uint8x16_t bR=botRight.val[ch];

                //horizontal gradient math: tR+ 2*mR+ bR -(tL+ 2*mL+ bL)
                uint16x8_t gxPosLow=vaddl_u8(vget_low_u8(tR),vget_low_u8(bR));
                uint16x8_t gxPosHigh=vaddl_u8(vget_high_u8(tR),vget_high_u8(bR));
                gxPosLow=vmlal_u8(gxPosLow,vget_low_u8(mR),vdup_n_u8(2));
                gxPosHigh=vmlal_u8(gxPosHigh,vget_high_u8(mR),vdup_n_u8(2));

                uint16x8_t gxNegLow=vaddl_u8(vget_low_u8(tL),vget_low_u8(bL));
                uint16x8_t gxNegHigh=vaddl_u8(vget_high_u8(tL),vget_high_u8(bL));
                gxNegLow=vmlal_u8(gxNegLow,vget_low_u8(mL),vdup_n_u8(2));
                gxNegHigh=vmlal_u8(gxNegHigh,vget_high_u8(mL),vdup_n_u8(2));

                uint16x8_t absGxLow=vabdq_u16(gxPosLow,gxNegLow);
                uint16x8_t absGxHigh=vabdq_u16(gxPosHigh,gxNegHigh);

                //vertical gradient math: bL+ 2*bC+ bR -(tL+ 2*tC+ tR)
                uint16x8_t gyPosLow=vaddl_u8(vget_low_u8(bL),vget_low_u8(bR));
                uint16x8_t gyPosHigh=vaddl_u8(vget_high_u8(bL),vget_high_u8(bR));
                gyPosLow=vmlal_u8(gyPosLow,vget_low_u8(bC),vdup_n_u8(2));
                gyPosHigh=vmlal_u8(gyPosHigh,vget_high_u8(bC),vdup_n_u8(2));

                uint16x8_t gyNegLow=vaddl_u8(vget_low_u8(tL),vget_low_u8(tR));
                uint16x8_t gyNegHigh=vaddl_u8(vget_high_u8(tL),vget_high_u8(tR));
                gyNegLow=vmlal_u8(gyNegLow,vget_low_u8(tC),vdup_n_u8(2));
                gyNegHigh=vmlal_u8(gyNegHigh,vget_high_u8(tC),vdup_n_u8(2));

                uint16x8_t absGyLow=vabdq_u16(gyPosLow,gyNegLow);
                uint16x8_t absGyHigh=vabdq_u16(gyPosHigh,gyNegHigh);

                //sum gradients and shift to get final value
                uint16x8_t magnitudeLow=vaddq_u16(absGxLow,absGyLow);
                uint16x8_t magnitudeHigh=vaddq_u16(absGxHigh,absGyHigh);

                uint8x8_t magLowByte=vqshrn_n_u16(magnitudeLow,1);
                uint8x8_t magHighByte=vqshrn_n_u16(magnitudeHigh,1);
                pixelResult.val[ch]=vcombine_u8(magLowByte,magHighByte);
            }

            //save all 16 results to output memory
            vst4q_u8(dstRow+ col* 4,pixelResult);
        }

        //handle the image edges and leftovers with normal cpu math
        auto scalarSobelAtCol=[&](int c){
            int gxR=0,gyR=0,gxG=0,gyG=0,gxB=0,gyB=0;

            for(int ky=-1; ky <= 1; ky++){
                for(int kx=-1; kx <= 1; kx++){
                    int neighborRow=clampIdx(row+ ky,height);
                    int neighborCol=clampIdx(c+ kx,width);
                    int pixelIdx=(neighborRow* width+ neighborCol)* 4;

                    int r=inputBuf[pixelIdx];
                    int g=inputBuf[pixelIdx+ 1];
                    int b=inputBuf[pixelIdx+ 2];

                    int gxWeight=(kx== -1)? -(ky== 0 ? 2 : 1)
                                 :(kx==  1)? (ky== 0 ? 2 : 1): 0;
                    int gyWeight=(ky== -1)? -(kx== 0 ? 2 : 1)
                                 :(ky==  1)? (kx== 0 ? 2 : 1): 0;

                    gxR+= r* gxWeight;  gyR+= r* gyWeight;
                    gxG+= g* gxWeight;  gyG+= g* gyWeight;
                    gxB+= b* gxWeight;  gyB+= b* gyWeight;
                }
            }

            int absGxR=gxR < 0 ? -gxR : gxR,absGyR=gyR < 0 ? -gyR : gyR;
            int absGxG=gxG < 0 ? -gxG : gxG,absGyG=gyG < 0 ? -gyG : gyG;
            int absGxB=gxB < 0 ? -gxB : gxB,absGyB=gyB < 0 ? -gyB : gyB;
            int magR=(absGxR+ absGyR)>> 1;
            int magG=(absGxG+ absGyG)>> 1;
            int magB=(absGxB+ absGyB)>> 1;

            int outOffset=c* 4;
            dstRow[outOffset]=(uint8_t)(magR > 255 ? 255 : magR);
            dstRow[outOffset+ 1]=(uint8_t)(magG > 255 ? 255 : magG);
            dstRow[outOffset+ 2]=(uint8_t)(magB > 255 ? 255 : magB);
            dstRow[outOffset+ 3]=255;
        };

        scalarSobelAtCol(0);
        for(int c=col; c < width; c++)
            scalarSobelAtCol(c);
    }

    env->ReleasePrimitiveArrayCritical(rgbaInput,inputBuf,JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(outputArray,outputBuf,0);

    return outputArray;
}

//verify if neon and cpu produce the same image content
extern "C" JNIEXPORT jstring JNICALL
Java_com_example_csproject_MainActivity_nativeVerifySobelCorrectness(
        JNIEnv* env,jobject thiz,
        jbyteArray rgbaInput,jint width,jint height){

    jsize len=env->GetArrayLength(rgbaInput);
    if(len < width* height* 4)return env->NewStringUTF("bad size");

    jbyteArray neonResult=Java_com_example_csproject_MainActivity_nativeSobelNeon(
            env,thiz,rgbaInput,width,height);

    uint8_t* src=(uint8_t*)env->GetPrimitiveArrayCritical(rgbaInput,nullptr);
    uint8_t* neonBuf=(uint8_t*)env->GetPrimitiveArrayCritical(neonResult,nullptr);

    int neonEnd=1;
    while(neonEnd+ 16 <= width - 1)neonEnd+= 16;

    int mismatches=0,worst=0,checked=0;

    //pixel by pixel comparison for center area
    for(int r=0; r < height; r++){
        int rUp=r > 0          ? r - 1 : 0;
        int rDown=r < height - 1 ? r+ 1 : height - 1;

        for(int c=1; c < neonEnd; c++){
            int cL=c - 1;
            int cR=c+ 1;
            int out=(r* width+ c)* 4;

            for(int ch=0; ch < 3; ch++){
                int tL=src[(rUp* width+ cL)* 4+ ch];
                int tC=src[(rUp* width+ c)* 4+ ch];
                int tR=src[(rUp* width+ cR)* 4+ ch];
                int mL=src[(r* width+ cL)* 4+ ch];
                int mR=src[(r* width+ cR)* 4+ ch];
                int bL=src[(rDown* width+ cL)* 4+ ch];
                int bC=src[(rDown* width+ c)* 4+ ch];
                int bR=src[(rDown* width+ cR)* 4+ ch];

                int gxPos=tR+ 2*mR+ bR;
                int gxNeg=tL+ 2*mL+ bL;
                int gyPos=bL+ 2*bC+ bR;
                int gyNeg=tL+ 2*tC+ tR;

                int absGx=gxPos > gxNeg ? gxPos - gxNeg : gxNeg - gxPos;
                int absGy=gyPos > gyNeg ? gyPos - gyNeg : gyNeg - gyPos;

                int mag=(absGx+ absGy)>> 1;
                uint8_t refMag=(uint8_t)(mag > 255 ? 255 : mag);

                int diff=(int)refMag -(int)neonBuf[out+ ch];
                if(diff < 0)diff=-diff;

                //count discrepancies larger than 1 level
                if(diff > 1){
                    mismatches++;
                    if(diff > worst)worst=diff;
                }
                checked++;
            }
        }
    }

    env->ReleasePrimitiveArrayCritical(rgbaInput,src,JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(neonResult,neonBuf,JNI_ABORT);
    env->DeleteLocalRef(neonResult);

    char msg[256];
    if(mismatches== 0){
        snprintf(msg,sizeof(msg),"pass: %d checked",checked);
    } else {
        snprintf(msg,sizeof(msg),"fail: %d errors",mismatches);
    }
    return env->NewStringUTF(msg);
}

//store context for the device gpu
static EGLDisplay g_eglDisplay=EGL_NO_DISPLAY;
static EGLContext g_eglContext=EGL_NO_CONTEXT;
static EGLSurface g_eglSurface=EGL_NO_SURFACE;

//shader program handles
static GLuint g_passthroughProgram=0;
static GLuint g_sobelProgram=0;

//output buffer handle
static GLuint g_ssboOut=0;
static int g_ssboBytes=0;

//texture handle for input data
static GLuint g_inputTex=0;
static int g_texW=0,g_texH=0;

//variants of sobel shaders with different tuning
static GLuint g_sobelImgProg_8x8=0;
static GLuint g_sobelImgProg_16x16=0;
static GLuint g_sobelImgProg_32x4=0;
static GLuint g_activeSobelImgProg=0;
static int g_activeWgX=16;
static int g_activeWgY=16;

struct SobelUniformLocs {
    GLint width=-1;
    GLint height=-1;
    GLint rowOffset=-1;
};

static SobelUniformLocs g_sobelLocs_8x8;
static SobelUniformLocs g_sobelLocs_16x16;
static SobelUniformLocs g_sobelLocs_32x4;
static SobelUniformLocs* g_activeSobelLocs=nullptr;

//double buffering to hide gpu readback latency
static constexpr int kGpuSobelReadbackDepth=2;
static GLuint g_gpuSobelOut[kGpuSobelReadbackDepth]={0,0};
static GLsync g_gpuSobelFence[kGpuSobelReadbackDepth]={0,0};
static int g_gpuSobelBytes=0;
static int g_gpuSobelCursor=0;
static bool   g_gpuSobelPrimed=false;

//variables for adaptive hybrid split
static int g_hybridMidRow=-1;
static double g_neonNsPerRow=0.0;
static double g_gpuNsPerRow=0.0;
static long g_lastNeonHalfNs=0;
static long g_lastGpuHalfNs=0;
static float  g_runtimeTempC=-1.0f;
static long g_runtimeE2eNs=0;
static int g_runtimeJitter=0;
static int g_lastHybridW=0;
static int g_lastHybridH=0;

//link c++ variables to shader variables
static SobelUniformLocs makeSobelUniformLocs(GLuint prog){
    SobelUniformLocs locs;
    if(prog== 0)return locs;
    locs.width=glGetUniformLocation(prog,"uWidth");
    locs.height=glGetUniformLocation(prog,"uHeight");
    locs.rowOffset=glGetUniformLocation(prog,"uRowOffset");
    return locs;
}

static SobelUniformLocs* sobelUniformsForProgram(GLuint prog){
    if(prog== g_sobelImgProg_8x8)return &g_sobelLocs_8x8;
    if(prog== g_sobelImgProg_16x16)return &g_sobelLocs_16x16;
    if(prog== g_sobelImgProg_32x4)return &g_sobelLocs_32x4;
    return nullptr;
}

//choose which shader variant to run
static void selectActiveSobelVariant(GLuint prog,int wgX,int wgY){
    g_activeSobelImgProg=prog;
    g_activeWgX=wgX;
    g_activeWgY=wgY;
    g_activeSobelLocs=sobelUniformsForProgram(prog);
}

//set the image size and offset inside the shader
static inline void setSobelUniforms(const SobelUniformLocs* locs,int width,int height,int rowOffset){
    if(!locs)return;
    if(locs->width >= 0)glUniform1i(locs->width,width);
    if(locs->height >= 0)glUniform1i(locs->height,height);
    if(locs->rowOffset >= 0)glUniform1i(locs->rowOffset,rowOffset);
}

//delete old gpu buffers
static void resetGpuSobelReadbackPipeline(){
    for(int i=0; i < kGpuSobelReadbackDepth; i++){
        if(g_gpuSobelFence[i] != nullptr){
            glDeleteSync(g_gpuSobelFence[i]);
            g_gpuSobelFence[i]=nullptr;
        }
        if(g_gpuSobelOut[i] != 0){
            glDeleteBuffers(1,&g_gpuSobelOut[i]);
            g_gpuSobelOut[i]=0;
        }
    }
    g_gpuSobelBytes=0;
    g_gpuSobelCursor=0;
    g_gpuSobelPrimed=false;
}

//make sure gpu result buffers are the right size
static bool ensureGpuSobelReadbackPipeline(int totalBytes){
    if(g_gpuSobelBytes== totalBytes && g_gpuSobelOut[0] != 0 && g_gpuSobelOut[1] != 0){
        return true;
    }

    resetGpuSobelReadbackPipeline();
    glGenBuffers(kGpuSobelReadbackDepth,g_gpuSobelOut);
    for(int i=0; i < kGpuSobelReadbackDepth; i++){
        if(g_gpuSobelOut[i]== 0)return false;
        glBindBuffer(GL_SHADER_STORAGE_BUFFER,g_gpuSobelOut[i]);
        glBufferData(GL_SHADER_STORAGE_BUFFER,totalBytes,nullptr,GL_DYNAMIC_READ);
    }
    g_gpuSobelBytes=totalBytes;
    return true;
}

//pause cpu until gpu is done with a specific job
static bool waitForGpuFence(GLsync* fencePtr){
    GLsync fence=*fencePtr;
    if(fence== nullptr)return false;

    //try to wait a few milliseconds,then wait as long as needed
    GLenum wait=glClientWaitSync(fence,0,0);
    if(wait== GL_TIMEOUT_EXPIRED){
        wait=glClientWaitSync(fence,GL_SYNC_FLUSH_COMMANDS_BIT,5* 1000* 1000);
    }
    if(wait== GL_TIMEOUT_EXPIRED){
        wait=glClientWaitSync(fence,GL_SYNC_FLUSH_COMMANDS_BIT,GL_TIMEOUT_IGNORED);
    }
    bool ok=wait== GL_ALREADY_SIGNALED || wait== GL_CONDITION_SATISFIED;
    glDeleteSync(fence);
*fencePtr=nullptr;
    return ok;
}

//turn glsl source text into a running gpu program
static GLuint compileComputeProgram(const char* source){
    GLuint shader=glCreateShader(GL_COMPUTE_SHADER);
    glShaderSource(shader,1,&source,nullptr);
    glCompileShader(shader);

    GLint ok=0;
    glGetShaderiv(shader,GL_COMPILE_STATUS,&ok);
    if(!ok){
        glDeleteShader(shader);
        return 0;
    }

    GLuint prog=glCreateProgram();
    glAttachShader(prog,shader);
    glLinkProgram(prog);
    glDeleteShader(shader);

    glGetProgramiv(prog,GL_LINK_STATUS,&ok);
    if(!ok){
        glDeleteProgram(prog);
        return 0;
    }
    return prog;
}

//gpu code: copy input directly to output(for testing)
static const char* PASSTHROUGH_SHADER_SRC=R"(#version 310 es
layout(local_size_x=8,local_size_y=8,local_size_z=1)in;

layout(std430,binding=0)readonly  buffer InputBuffer  { uint inputData[];  };
layout(std430,binding=1)writeonly buffer OutputBuffer { uint outputData[]; };

uniform int uWidth;
uniform int uHeight;

void main(){
    uint x=gl_GlobalInvocationID.x;
    uint y=gl_GlobalInvocationID.y;
    if(x >= uint(uWidth)|| y >= uint(uHeight))return;
    uint idx=y* uint(uWidth)+ x;
    outputData[idx]=inputData[idx];
}
)";

//gpu code: sobel edge detection using linear buffers
static const char* SOBEL_SHADER_SRC=R"(#version 310 es
layout(local_size_x=8,local_size_y=8,local_size_z=1)in;

layout(std430,binding=0)readonly  buffer InputBuffer  { uint inputPixels[];  };
layout(std430,binding=1)writeonly buffer OutputBuffer { uint outputPixels[]; };

uniform int uWidth;
uniform int uHeight;
uniform int uRowOffset;

uint chan(uint pixel,int ch){
    return(pixel >> uint(ch* 8))& 0xFFu;
}

uint fetchPixel(int col,int row){
    col=clamp(col,0,uWidth  - 1);
    row=clamp(row,0,uHeight - 1);
    return inputPixels[row* uWidth+ col];
}

void main(){
    int col=int(gl_GlobalInvocationID.x);
    int row=int(gl_GlobalInvocationID.y)+ uRowOffset;
    if(col >= uWidth || row >= uHeight)return;

    uint tL=fetchPixel(col-1,row-1);  uint tC=fetchPixel(col,row-1);  uint tR=fetchPixel(col+1,row-1);
    uint mL=fetchPixel(col-1,row);                                        uint mR=fetchPixel(col+1,row);
    uint bL=fetchPixel(col-1,row+1);  uint bC=fetchPixel(col,row+1);  uint bR=fetchPixel(col+1,row+1);

    uint outChannels[3];
    for(int ch=0; ch < 3; ch++){
        int tLv=int(chan(tL,ch));  int tCv=int(chan(tC,ch));  int tRv=int(chan(tR,ch));
        int mLv=int(chan(mL,ch));                               int mRv=int(chan(mR,ch));
        int bLv=int(chan(bL,ch));  int bCv=int(chan(bC,ch));  int bRv=int(chan(bR,ch));

        int absGx=abs((tRv+ 2*mRv+ bRv)-(tLv+ 2*mLv+ bLv));
        int absGy=abs((bLv+ 2*bCv+ bRv)-(tLv+ 2*tCv+ tRv));

        outChannels[ch]=uint(clamp((absGx+ absGy)>> 1,0,255));
    }

    outputPixels[(row - uRowOffset)* uWidth+ col]=
       (255u << 24u)|(outChannels[2] << 16u)|(outChannels[1] << 8u)| outChannels[0];
}
)";

//gpu code: optimized sobel using texture caches
static const char* SOBEL_IMAGE_SHADER_TEMPLATE=R"(#version 310 es
layout(local_size_x=%d,local_size_y=%d,local_size_z=1)in;

layout(rgba8,binding=0)readonly uniform highp image2D uInputImg;
layout(std430,binding=1)writeonly buffer OutputBuffer { uint outputPixels[]; };

uniform int uWidth;
uniform int uHeight;
uniform int uRowOffset;

ivec3 fetchRGB(int col,int row){
    col=clamp(col,0,uWidth  - 1);
    row=clamp(row,0,uHeight - 1);
    vec4 p=imageLoad(uInputImg,ivec2(col,row));
    return ivec3(p.rgb* 255.0+ vec3(0.5));
}

void main(){
    int col=int(gl_GlobalInvocationID.x);
    int row=int(gl_GlobalInvocationID.y)+ uRowOffset;
    if(col >= uWidth || row >= uHeight)return;

    ivec3 tL=fetchRGB(col-1,row-1);  ivec3 tC=fetchRGB(col,row-1);  ivec3 tR=fetchRGB(col+1,row-1);
    ivec3 mL=fetchRGB(col-1,row);                                      ivec3 mR=fetchRGB(col+1,row);
    ivec3 bL=fetchRGB(col-1,row+1);  ivec3 bC=fetchRGB(col,row+1);  ivec3 bR=fetchRGB(col+1,row+1);

    ivec3 absGx=abs((tR+ 2*mR+ bR)-(tL+ 2*mL+ bL));
    ivec3 absGy=abs((bL+ 2*bC+ bR)-(tL+ 2*tC+ tR));

    ivec3 mag=clamp((absGx+ absGy)>> 1,ivec3(0),ivec3(255));

    outputPixels[(row - uRowOffset)* uWidth+ col]=
       (255u << 24u)|(uint(mag.b)<< 16u)|(uint(mag.g)<< 8u)| uint(mag.r);
}
)";

static GLuint compileSobelImageVariant(int wgX,int wgY){
    char src[4096];
    snprintf(src,sizeof(src),SOBEL_IMAGE_SHADER_TEMPLATE,wgX,wgY);
    return compileComputeProgram(src);
}

//setup headless compute context and build shaders
extern "C" JNIEXPORT jstring JNICALL
Java_com_example_csproject_MainActivity_nativeInitGpu(JNIEnv* env,jobject){

    if(g_eglContext != EGL_NO_CONTEXT){
        return env->NewStringUTF("gpu ok");
    }

    //find and open the mobile gpu driver
    g_eglDisplay=eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if(g_eglDisplay== EGL_NO_DISPLAY){
        return env->NewStringUTF("gpu fail");
    }

    EGLint major=0,minor=0;
    if(!eglInitialize(g_eglDisplay,&major,&minor)){
        return env->NewStringUTF("gpu fail");
    }

    //request a surface that supports compute and 8-bit colors
    const EGLint configAttribs[]={
        EGL_RENDERABLE_TYPE,EGL_OPENGL_ES3_BIT,
        EGL_SURFACE_TYPE,EGL_PBUFFER_BIT,
        EGL_RED_SIZE,8,
        EGL_GREEN_SIZE,8,
        EGL_BLUE_SIZE,8,
        EGL_ALPHA_SIZE,8,
        EGL_NONE
    };
    EGLConfig config;
    EGLint numConfigs=0;
    if(!eglChooseConfig(g_eglDisplay,configAttribs,&config,1,&numConfigs)|| numConfigs== 0){
        return env->NewStringUTF("gpu fail");
    }

    eglBindAPI(EGL_OPENGL_ES_API);

    //create a hidden context for background compute
    const EGLint contextAttribs[]={
        EGL_CONTEXT_CLIENT_VERSION,3,
        EGL_NONE
    };
    g_eglContext=eglCreateContext(g_eglDisplay,config,EGL_NO_CONTEXT,contextAttribs);
    if(g_eglContext== EGL_NO_CONTEXT){
        return env->NewStringUTF("gpu fail");
    }

    //small dummy surface needed to make the context active
    const EGLint pbufferAttribs[]={
        EGL_WIDTH,1,
        EGL_HEIGHT,1,
        EGL_NONE
    };
    g_eglSurface=eglCreatePbufferSurface(g_eglDisplay,config,pbufferAttribs);
    if(g_eglSurface== EGL_NO_SURFACE){
        return env->NewStringUTF("gpu fail");
    }

    //link context to current thread
    if(!eglMakeCurrent(g_eglDisplay,g_eglSurface,g_eglSurface,g_eglContext)){
        return env->NewStringUTF("gpu fail");
    }

    //compile all shader variants
    g_passthroughProgram=compileComputeProgram(PASSTHROUGH_SHADER_SRC);
    if(g_passthroughProgram== 0){
        return env->NewStringUTF("gpu fail");
    }

    g_sobelProgram=compileComputeProgram(SOBEL_SHADER_SRC);
    if(g_sobelProgram== 0){
        return env->NewStringUTF("gpu fail");
    }

    g_sobelImgProg_8x8=compileSobelImageVariant(8,8);
    g_sobelImgProg_16x16=compileSobelImageVariant(16,16);
    g_sobelImgProg_32x4=compileSobelImageVariant(32,4);
    
    g_sobelLocs_8x8=makeSobelUniformLocs(g_sobelImgProg_8x8);
    g_sobelLocs_16x16=makeSobelUniformLocs(g_sobelImgProg_16x16);
    g_sobelLocs_32x4=makeSobelUniformLocs(g_sobelImgProg_32x4);

    //select a safe default variant
    if     (g_sobelImgProg_16x16 != 0)selectActiveSobelVariant(g_sobelImgProg_16x16,16,16);
    else if(g_sobelImgProg_8x8   != 0)selectActiveSobelVariant(g_sobelImgProg_8x8,8,8);
    else                                selectActiveSobelVariant(g_sobelImgProg_32x4,32,4);

    return env->NewStringUTF("gpu ok");
}

//verify gpu memory throughput
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_example_csproject_MainActivity_nativeGpuPassThrough(
        JNIEnv* env,jobject,
        jbyteArray rgbaInput,jint width,jint height){

    jsize len=env->GetArrayLength(rgbaInput);
    if(len < width* height* 4)return env->NewByteArray(0);

    int totalBytes=width* height* 4;
    jbyteArray outputArray=env->NewByteArray(totalBytes);

    if(g_passthroughProgram== 0 || g_eglContext== EGL_NO_CONTEXT){
        return outputArray;
    }

    jbyte* srcBytes=(jbyte*)env->GetPrimitiveArrayCritical(rgbaInput,nullptr);
    jbyte* dstBytes=(jbyte*)env->GetPrimitiveArrayCritical(outputArray,nullptr);

    //upload and download buffers
    GLuint ssboIn=0,ssboOut=0;
    glGenBuffers(1,&ssboIn);
    glBindBuffer(GL_SHADER_STORAGE_BUFFER,ssboIn);
    glBufferData(GL_SHADER_STORAGE_BUFFER,totalBytes,srcBytes,GL_STATIC_READ);

    glGenBuffers(1,&ssboOut);
    glBindBuffer(GL_SHADER_STORAGE_BUFFER,ssboOut);
    glBufferData(GL_SHADER_STORAGE_BUFFER,totalBytes,nullptr,GL_STATIC_COPY);

    //run pass-through shader
    glUseProgram(g_passthroughProgram);
    glBindBufferBase(GL_SHADER_STORAGE_BUFFER,0,ssboIn);
    glBindBufferBase(GL_SHADER_STORAGE_BUFFER,1,ssboOut);

    GLint locW=glGetUniformLocation(g_passthroughProgram,"uWidth");
    GLint locH=glGetUniformLocation(g_passthroughProgram,"uHeight");
    glUniform1i(locW,width);
    glUniform1i(locH,height);

    GLuint groupsX=(GLuint)(width+ 7)/ 8;
    GLuint groupsY=(GLuint)(height+ 7)/ 8;
    glDispatchCompute(groupsX,groupsY,1);

    glMemoryBarrier(GL_ALL_BARRIER_BITS);

    //map result memory back to cpu
    glBindBuffer(GL_SHADER_STORAGE_BUFFER,ssboOut);
    void* gpuData=glMapBufferRange(GL_SHADER_STORAGE_BUFFER,0,totalBytes,GL_MAP_READ_BIT);
    if(gpuData){
        memcpy(dstBytes,gpuData,totalBytes);
        glUnmapBuffer(GL_SHADER_STORAGE_BUFFER);
    }

    glDeleteBuffers(1,&ssboIn);
    glDeleteBuffers(1,&ssboOut);

    env->ReleasePrimitiveArrayCritical(rgbaInput,srcBytes,JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(outputArray,dstBytes,0);

    return outputArray;
}

//verify gpu data transfer accuracy
extern "C" JNIEXPORT jstring JNICALL
Java_com_example_csproject_MainActivity_nativeVerifyGpuPassThrough(
        JNIEnv* env,jobject thiz,
        jbyteArray rgbaInput,jint width,jint height){

    jsize len=env->GetArrayLength(rgbaInput);
    if(len < width* height* 4)return env->NewStringUTF("gpu fail");

    jbyteArray gpuOutput=Java_com_example_csproject_MainActivity_nativeGpuPassThrough(
            env,thiz,rgbaInput,width,height);

    int totalBytes=width* height* 4;
    jbyte* src=(jbyte*)env->GetPrimitiveArrayCritical(rgbaInput,nullptr);
    jbyte* dst=(jbyte*)env->GetPrimitiveArrayCritical(gpuOutput,nullptr);

    int mismatches=0;
    for(int i=0; i < totalBytes; i++){
        if(src[i] != dst[i])mismatches++;
    }

    env->ReleasePrimitiveArrayCritical(rgbaInput,src,JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(gpuOutput,dst,JNI_ABORT);
    env->DeleteLocalRef(gpuOutput);

    if(mismatches== 0)return env->NewStringUTF("gpu pass");
    return env->NewStringUTF("gpu fail");
}

//sobel filter using parallel gpu cores
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_example_csproject_MainActivity_nativeGpuSobel(
        JNIEnv* env,jobject,
        jbyteArray rgbaInput,jint width,jint height){

    jsize len=env->GetArrayLength(rgbaInput);
    if(len < width* height* 4)return env->NewByteArray(0);

    int totalBytes=width* height* 4;
    jbyteArray outputArray=env->NewByteArray(totalBytes);

    if(g_activeSobelImgProg== 0 || g_eglContext== EGL_NO_CONTEXT){
        return outputArray;
    }

    jbyte* srcBytes=(jbyte*)env->GetPrimitiveArrayCritical(rgbaInput,nullptr);
    jbyte* dstBytes=(jbyte*)env->GetPrimitiveArrayCritical(outputArray,nullptr);

    //update texture data if image size changed
    if(g_inputTex== 0 || g_texW != width || g_texH != height){
        if(g_inputTex != 0)glDeleteTextures(1,&g_inputTex);
        glGenTextures(1,&g_inputTex);
        glBindTexture(GL_TEXTURE_2D,g_inputTex);
        glTexStorage2D(GL_TEXTURE_2D,1,GL_RGBA8,width,height);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
        g_texW=width; g_texH=height;
    }
    glBindTexture(GL_TEXTURE_2D,g_inputTex);
    glTexSubImage2D(GL_TEXTURE_2D,0,0,0,width,height,GL_RGBA,GL_UNSIGNED_BYTE,srcBytes);

    //setup multiple result buffers for efficiency
    if(!ensureGpuSobelReadbackPipeline(totalBytes)){
        env->ReleasePrimitiveArrayCritical(rgbaInput,srcBytes,JNI_ABORT);
        env->ReleasePrimitiveArrayCritical(outputArray,dstBytes,0);
        return outputArray;
    }

    const int writeSlot=g_gpuSobelCursor;
    const int readSlot=g_gpuSobelPrimed ?(writeSlot+ 1)% kGpuSobelReadbackDepth : writeSlot;

    //launch gpu compute kernel
    glUseProgram(g_activeSobelImgProg);
    glBindImageTexture(0,g_inputTex,0,GL_FALSE,0,GL_READ_ONLY,GL_RGBA8);
    glBindBufferBase(GL_SHADER_STORAGE_BUFFER,1,g_gpuSobelOut[writeSlot]);
    setSobelUniforms(g_activeSobelLocs,width,height,0);

    GLuint groupsX=(GLuint)(width+ g_activeWgX - 1)/ g_activeWgX;
    GLuint groupsY=(GLuint)(height+ g_activeWgY - 1)/ g_activeWgY;
    glDispatchCompute(groupsX,groupsY,1);

    //fence prevents cpu from reading buffer too early
    glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
    if(g_gpuSobelFence[writeSlot] != nullptr)glDeleteSync(g_gpuSobelFence[writeSlot]);
    g_gpuSobelFence[writeSlot]=glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE,0);

    //read previously finished gpu work while this one runs
    if(waitForGpuFence(&g_gpuSobelFence[readSlot])){
        glBindBuffer(GL_SHADER_STORAGE_BUFFER,g_gpuSobelOut[readSlot]);
        void* gpuData=glMapBufferRange(GL_SHADER_STORAGE_BUFFER,0,totalBytes,GL_MAP_READ_BIT);
        if(gpuData){
            memcpy(dstBytes,gpuData,totalBytes);
            glUnmapBuffer(GL_SHADER_STORAGE_BUFFER);
        }
    }

    g_gpuSobelPrimed=true;
    g_gpuSobelCursor=(writeSlot+ 1)% kGpuSobelReadbackDepth;

    env->ReleasePrimitiveArrayCritical(rgbaInput,srcBytes,JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(outputArray,dstBytes,0);

    return outputArray;
}

//check gpu output matches scalar reference
extern "C" JNIEXPORT jstring JNICALL
Java_com_example_csproject_MainActivity_nativeVerifyGpuSobel(
        JNIEnv* env,jobject thiz,
        jbyteArray rgbaInput,jint width,jint height){

    jsize len=env->GetArrayLength(rgbaInput);
    if(len < width* height* 4)return env->NewStringUTF("bad size");

    jbyteArray gpuWarmup=Java_com_example_csproject_MainActivity_nativeGpuSobel(env,thiz,rgbaInput,width,height);
    env->DeleteLocalRef(gpuWarmup);
    jbyteArray gpuResult=Java_com_example_csproject_MainActivity_nativeGpuSobel(env,thiz,rgbaInput,width,height);
    jbyteArray scalarResult=Java_com_example_csproject_MainActivity_nativeSobelFilter(env,thiz,rgbaInput,width,height);

    int totalBytes=width* height* 4;
    uint8_t* gpuBuf=(uint8_t*)env->GetPrimitiveArrayCritical(gpuResult,nullptr);
    uint8_t* scalarBuf=(uint8_t*)env->GetPrimitiveArrayCritical(scalarResult,nullptr);

    int mismatches=0;
    for(int i=0; i < totalBytes; i++){
        int diff=(int)gpuBuf[i] -(int)scalarBuf[i];
        if(diff < 0)diff=-diff;
        //slightly larger tolerance(2 levels)for gpu math artifacts
        if(diff > 2)mismatches++;
    }

    env->ReleasePrimitiveArrayCritical(gpuResult,gpuBuf,JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(scalarResult,scalarBuf,JNI_ABORT);
    env->DeleteLocalRef(gpuResult);
    env->DeleteLocalRef(scalarResult);

    if(mismatches== 0)return env->NewStringUTF("gpu pass");
    return env->NewStringUTF("gpu fail");
}

//catch system info from kotlin UI
extern "C" JNIEXPORT void JNICALL
Java_com_example_csproject_MainActivity_nativeSetRuntimeHints(
        JNIEnv*,jobject,jfloat tempC,jlong e2eNs,jint jitterCount){
    g_runtimeTempC=tempC;
    g_runtimeE2eNs=e2eNs;
    g_runtimeJitter=jitterCount;
}

//neon-specific row processing for hybrid mode
static void sobelNeonRows(const uint8_t* inputBuf,uint8_t* outputBuf,
                           int width,int height,int startRow,int endRow){

    for(int row=startRow; row < endRow; row++){

        uint8_t* dstRow=outputBuf+ row* width* 4;

        int prevRowIdx=(row > 0)? row - 1 : 0;
        int nextRowIdx=(row < height - 1)? row+ 1 : height - 1;
        const uint8_t* topRowPtr=inputBuf+ prevRowIdx* width* 4;
        const uint8_t* midRowPtr=inputBuf+ row* width* 4;
        const uint8_t* botRowPtr=inputBuf+ nextRowIdx* width* 4;

        if(row+ 2 < height){
            const uint8_t* prefetchRow=inputBuf+(row+ 2)* width* 4;
            for(int p=0; p < width* 4; p+= 64)
                __builtin_prefetch(prefetchRow+ p,0,1);
        }

        int col=1;
        for(; col+ 16 <= width - 1; col+= 16){

            __builtin_prefetch(topRowPtr+(col+ 32)* 4,0,2);
            __builtin_prefetch(midRowPtr+(col+ 32)* 4,0,2);
            __builtin_prefetch(botRowPtr+(col+ 32)* 4,0,2);
            __builtin_prefetch(dstRow+(col+ 32)* 4,1,1);

            uint8x16x4_t topLeft=vld4q_u8(topRowPtr+(col - 1)* 4);
            uint8x16x4_t topCenter=vld4q_u8(topRowPtr+  col* 4);
            uint8x16x4_t topRight=vld4q_u8(topRowPtr+(col+ 1)* 4);
            uint8x16x4_t midLeft=vld4q_u8(midRowPtr+(col - 1)* 4);
            uint8x16x4_t midRight=vld4q_u8(midRowPtr+(col+ 1)* 4);
            uint8x16x4_t botLeft=vld4q_u8(botRowPtr+(col - 1)* 4);
            uint8x16x4_t botCenter=vld4q_u8(botRowPtr+  col* 4);
            uint8x16x4_t botRight=vld4q_u8(botRowPtr+(col+ 1)* 4);

            uint8x16x4_t pixelResult;
            pixelResult.val[3]=vdupq_n_u8(255);

            for(int ch=0; ch < 3; ch++){
                uint8x16_t tL=topLeft.val[ch];
                uint8x16_t tC=topCenter.val[ch];
                uint8x16_t tR=topRight.val[ch];
                uint8x16_t mL=midLeft.val[ch];
                uint8x16_t mR=midRight.val[ch];
                uint8x16_t bL=botLeft.val[ch];
                uint8x16_t bC=botCenter.val[ch];
                uint8x16_t bR=botRight.val[ch];

                uint16x8_t gxPosLow=vaddl_u8(vget_low_u8(tR),vget_low_u8(bR));
                uint16x8_t gxPosHigh=vaddl_u8(vget_high_u8(tR),vget_high_u8(bR));
                gxPosLow=vmlal_u8(gxPosLow,vget_low_u8(mR),vdup_n_u8(2));
                gxPosHigh=vmlal_u8(gxPosHigh,vget_high_u8(mR),vdup_n_u8(2));

                uint16x8_t gxNegLow=vaddl_u8(vget_low_u8(tL),vget_low_u8(bL));
                uint16x8_t gxNegHigh=vaddl_u8(vget_high_u8(tL),vget_high_u8(bL));
                gxNegLow=vmlal_u8(gxNegLow,vget_low_u8(mL),vdup_n_u8(2));
                gxNegHigh=vmlal_u8(gxNegHigh,vget_high_u8(mL),vdup_n_u8(2));

                uint16x8_t absGxLow=vabdq_u16(gxPosLow,gxNegLow);
                uint16x8_t absGxHigh=vabdq_u16(gxPosHigh,gxNegHigh);

                uint16x8_t gyPosLow=vaddl_u8(vget_low_u8(bL),vget_low_u8(bR));
                uint16x8_t gyPosHigh=vaddl_u8(vget_high_u8(bL),vget_high_u8(bR));
                gyPosLow=vmlal_u8(gyPosLow,vget_low_u8(bC),vdup_n_u8(2));
                gyPosHigh=vmlal_u8(gyPosHigh,vget_high_u8(bC),vdup_n_u8(2));

                uint16x8_t gyNegLow=vaddl_u8(vget_low_u8(tL),vget_low_u8(tR));
                uint16x8_t gyNegHigh=vaddl_u8(vget_high_u8(tL),vget_high_u8(tR));
                gyNegLow=vmlal_u8(gyNegLow,vget_low_u8(tC),vdup_n_u8(2));
                gyNegHigh=vmlal_u8(gyNegHigh,vget_high_u8(tC),vdup_n_u8(2));

                uint16x8_t absGyLow=vabdq_u16(gyPosLow,gyNegLow);
                uint16x8_t absGyHigh=vabdq_u16(gyPosHigh,gyNegHigh);

                uint16x8_t magnitudeLow=vaddq_u16(absGxLow,absGyLow);
                uint16x8_t magnitudeHigh=vaddq_u16(absGxHigh,absGyHigh);

                uint8x8_t magLowByte=vqshrn_n_u16(magnitudeLow,1);
                uint8x8_t magHighByte=vqshrn_n_u16(magnitudeHigh,1);
                pixelResult.val[ch]=vcombine_u8(magLowByte,magHighByte);
            }

            vst4q_u8(dstRow+ col* 4,pixelResult);
        }

        auto scalarSobelAtCol=[&](int c){
            int gxR=0,gyR=0,gxG=0,gyG=0,gxB=0,gyB=0;

            for(int ky=-1; ky <= 1; ky++){
                for(int kx=-1; kx <= 1; kx++){
                    int neighborRow=clampIdx(row+ ky,height);
                    int neighborCol=clampIdx(c+ kx,width);
                    int pixelIdx=(neighborRow* width+ neighborCol)* 4;

                    int r=inputBuf[pixelIdx];
                    int g=inputBuf[pixelIdx+ 1];
                    int b=inputBuf[pixelIdx+ 2];

                    int gxWeight=(kx== -1)? -(ky== 0 ? 2 : 1)
                                 :(kx==  1)? (ky== 0 ? 2 : 1): 0;
                    int gyWeight=(ky== -1)? -(kx== 0 ? 2 : 1)
                                 :(ky==  1)? (kx== 0 ? 2 : 1): 0;

                    gxR+= r* gxWeight;  gyR+= r* gyWeight;
                    gxG+= g* gxWeight;  gyG+= g* gyWeight;
                    gxB+= b* gxWeight;  gyB+= b* gyWeight;
                }
            }

            int absGxR=gxR < 0 ? -gxR : gxR,absGyR=gyR < 0 ? -gyR : gyR;
            int absGxG=gxG < 0 ? -gxG : gxG,absGyG=gyG < 0 ? -gyG : gyG;
            int absGxB=gxB < 0 ? -gxB : gxB,absGyB=gyB < 0 ? -gyB : gyB;
            int magR=(absGxR+ absGyR)>> 1;
            int magG=(absGxG+ absGyG)>> 1;
            int magB=(absGxB+ absGyB)>> 1;

            int outOffset=c* 4;
            dstRow[outOffset]=(uint8_t)(magR > 255 ? 255 : magR);
            dstRow[outOffset+ 1]=(uint8_t)(magG > 255 ? 255 : magG);
            dstRow[outOffset+ 2]=(uint8_t)(magB > 255 ? 255 : magB);
            dstRow[outOffset+ 3]=255;
        };

        scalarSobelAtCol(0);
        for(int c=col; c < width; c++)
            scalarSobelAtCol(c);
    }
}

//hybrid mode: split frame between cpu(neon)and gpu
//neon thread runs the top half,while gpu runs the bottom half simultaneously
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_example_csproject_MainActivity_nativeHybridSobel(
        JNIEnv* env,jobject,
        jbyteArray rgbaInput,jint width,jint height){

    jsize len=env->GetArrayLength(rgbaInput);
    if(len < width* height* 4)return env->NewByteArray(0);

    int totalBytes=width* height* 4;
    jbyteArray outputArray=env->NewByteArray(totalBytes);

    //fallback to cpu-only neon if gpu initialization failed
    if(g_activeSobelImgProg== 0 || g_eglContext== EGL_NO_CONTEXT){
        uint8_t* src=(uint8_t*)env->GetPrimitiveArrayCritical(rgbaInput,nullptr);
        uint8_t* dst=(uint8_t*)env->GetPrimitiveArrayCritical(outputArray,nullptr);
        sobelNeonRows(src,dst,width,height,0,height);
        env->ReleasePrimitiveArrayCritical(rgbaInput,src,JNI_ABORT);
        env->ReleasePrimitiveArrayCritical(outputArray,dst,0);
        return outputArray;
    }

    uint8_t* inputBuf=(uint8_t*)env->GetPrimitiveArrayCritical(rgbaInput,nullptr);
    uint8_t* outputBuf=(uint8_t*)env->GetPrimitiveArrayCritical(outputArray,nullptr);

    //initialize adaptive split point based on frame resolution
    if(width != g_lastHybridW || height != g_lastHybridH){
        g_hybridMidRow=height / 2;
        g_neonNsPerRow=0.0;
        g_gpuNsPerRow=0.0;
        g_lastHybridW=width;
        g_lastHybridH=height;
    }
    if(g_hybridMidRow < 0)g_hybridMidRow=height / 2;
    int midRow=g_hybridMidRow;
    int topRows=midRow;
    int bottomRows=height - midRow;
    int bottomBytes=bottomRows* width* 4;

    long neonHalfNs=0;
    long gpuHalfNs=0;

    //spin up a thread for the top half(neon cpu)
    std::thread neonThread;
    if(topRows > 0){
        neonThread=std::thread([&](){
            struct timespec a,b;
            clock_gettime(CLOCK_MONOTONIC,&a);
            sobelNeonRows(inputBuf,outputBuf,width,height,0,topRows);
            clock_gettime(CLOCK_MONOTONIC,&b);
            neonHalfNs=(b.tv_sec - a.tv_sec)* 1000000000L+(b.tv_nsec - a.tv_nsec);
        });
    }

    //execute the bottom half on gpu in the current thread
    if(bottomRows > 0){
        struct timespec g0,g1;
        clock_gettime(CLOCK_MONOTONIC,&g0);

        //texture data update
        if(g_inputTex== 0 || g_texW != width || g_texH != height){
            if(g_inputTex != 0)glDeleteTextures(1,&g_inputTex);
            glGenTextures(1,&g_inputTex);
            glBindTexture(GL_TEXTURE_2D,g_inputTex);
            glTexStorage2D(GL_TEXTURE_2D,1,GL_RGBA8,width,height);
            g_texW=width; g_texH=height;
        }
        glBindTexture(GL_TEXTURE_2D,g_inputTex);
        glTexSubImage2D(GL_TEXTURE_2D,0,0,0,width,height,GL_RGBA,GL_UNSIGNED_BYTE,inputBuf);

        //ensure storage buffer is allocated and large enough
        if(g_ssboOut== 0)glGenBuffers(1,&g_ssboOut);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER,g_ssboOut);
        if(g_ssboBytes != totalBytes){
            glBufferData(GL_SHADER_STORAGE_BUFFER,totalBytes,nullptr,GL_DYNAMIC_DRAW);
            g_ssboBytes=totalBytes;
        }

        //run the gpu variant and set row offset
        glUseProgram(g_activeSobelImgProg);
        glBindImageTexture(0,g_inputTex,0,GL_FALSE,0,GL_READ_ONLY,GL_RGBA8);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER,1,g_ssboOut);
        setSobelUniforms(g_activeSobelLocs,width,height,midRow);

        GLuint groupsX=(GLuint)(width + g_activeWgX - 1)/ g_activeWgX;
        GLuint groupsY=(GLuint)(bottomRows+ g_activeWgY - 1)/ g_activeWgY;
        glDispatchCompute(groupsX,groupsY,1);
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);

        //retrieve only the rows gpu actually processed
        glBindBuffer(GL_SHADER_STORAGE_BUFFER,g_ssboOut);
        void* gpuData=glMapBufferRange(GL_SHADER_STORAGE_BUFFER,0,bottomBytes,GL_MAP_READ_BIT);
        if(gpuData){
            memcpy(outputBuf+ midRow* width* 4,gpuData,bottomBytes);
            glUnmapBuffer(GL_SHADER_STORAGE_BUFFER);
        }

        clock_gettime(CLOCK_MONOTONIC,&g1);
        gpuHalfNs=(g1.tv_sec - g0.tv_sec)* 1000000000L+(g1.tv_nsec - g0.tv_nsec);
    }

    //wait for cpu part to finish
    if(neonThread.joinable())neonThread.join();

    //calculate adaptive split for next frame to balance workload
    const double alpha=(g_runtimeE2eNs > 40LL* 1000* 1000 || g_runtimeTempC >= 40.5f)? 0.25 : 0.10;
    if(topRows > 0 && neonHalfNs > 0){
        double sample=(double)neonHalfNs / topRows;
        g_neonNsPerRow=g_neonNsPerRow== 0.0 ? sample : g_neonNsPerRow*(1.0 - alpha)+ sample* alpha;
    }
    if(bottomRows > 0 && gpuHalfNs > 0){
        double sample=(double)gpuHalfNs / bottomRows;
        g_gpuNsPerRow=g_gpuNsPerRow== 0.0 ? sample : g_gpuNsPerRow*(1.0 - alpha)+ sample* alpha;
    }
    if(g_neonNsPerRow > 0.0 && g_gpuNsPerRow > 0.0){
        double frac=g_gpuNsPerRow /(g_neonNsPerRow+ g_gpuNsPerRow);
        if(g_runtimeTempC >= 40.5f)frac -= 0.03; //shift more work to cpu if thermal throttling detected
        if(frac < 0.10)frac=0.10;
        if(frac > 0.90)frac=0.90;
        g_hybridMidRow=(int)(height* frac+ 0.5);
    }
    g_lastNeonHalfNs=neonHalfNs;
    g_lastGpuHalfNs=gpuHalfNs;

    env->ReleasePrimitiveArrayCritical(rgbaInput,inputBuf,JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(outputArray,outputBuf,0);

    return outputArray;
}

//share hybrid timing data with kotlin
extern "C" JNIEXPORT void JNICALL
Java_com_example_csproject_MainActivity_nativeGetHybridStats(
        JNIEnv* env,jobject,jlongArray outBuf){
    jlong vals[3]={(jlong)g_hybridMidRow,(jlong)g_lastNeonHalfNs,(jlong)g_lastGpuHalfNs};
    env->SetLongArrayRegion(outBuf,0,3,vals);
}

//test which workgroup configuration performs best on this specific mobile hardware
extern "C" JNIEXPORT jstring JNICALL
Java_com_example_csproject_MainActivity_nativeBenchmarkGpuVariants(
        JNIEnv* env,jobject,
        jbyteArray rgbaInput,jint width,jint height){

    if(g_eglContext== EGL_NO_CONTEXT)return env->NewStringUTF("gpu fail");

    struct Variant { GLuint prog; int wgX,wgY; const char* name; };
    Variant variants[3]={
        { g_sobelImgProg_8x8,8,8,"8x8"   },
        { g_sobelImgProg_16x16,16,16,"16x16" },
        { g_sobelImgProg_32x4,32,4,"32x4"  },
    };

    int totalBytes=width* height* 4;
    jbyte* srcBytes=(jbyte*)env->GetPrimitiveArrayCritical(rgbaInput,nullptr);

    //populate texture for benchmarking
    if(g_inputTex== 0 || g_texW != width || g_texH != height){
        if(g_inputTex != 0)glDeleteTextures(1,&g_inputTex);
        glGenTextures(1,&g_inputTex);
        glBindTexture(GL_TEXTURE_2D,g_inputTex);
        glTexStorage2D(GL_TEXTURE_2D,1,GL_RGBA8,width,height);
        g_texW=width; g_texH=height;
    }
    glBindTexture(GL_TEXTURE_2D,g_inputTex);
    glTexSubImage2D(GL_TEXTURE_2D,0,0,0,width,height,GL_RGBA,GL_UNSIGNED_BYTE,srcBytes);
    env->ReleasePrimitiveArrayCritical(rgbaInput,srcBytes,JNI_ABORT);

    if(g_ssboOut== 0)glGenBuffers(1,&g_ssboOut);
    glBindBuffer(GL_SHADER_STORAGE_BUFFER,g_ssboOut);
    if(g_ssboBytes != totalBytes){
        glBufferData(GL_SHADER_STORAGE_BUFFER,totalBytes,nullptr,GL_DYNAMIC_DRAW);
        g_ssboBytes=totalBytes;
    }

    glBindImageTexture(0,g_inputTex,0,GL_FALSE,0,GL_READ_ONLY,GL_RGBA8);
    glBindBufferBase(GL_SHADER_STORAGE_BUFFER,1,g_ssboOut);

    double timings[3]={ 1e18,1e18,1e18 };

    //profile each variant
    for(int v=0; v < 3; v++){
        if(variants[v].prog== 0)continue;
        glUseProgram(variants[v].prog);
        setSobelUniforms(sobelUniformsForProgram(variants[v].prog),width,height,0);

        GLuint groupsX=(GLuint)(width+ variants[v].wgX - 1)/ variants[v].wgX;
        GLuint groupsY=(GLuint)(height+ variants[v].wgY - 1)/ variants[v].wgY;

        //warmup run
        glDispatchCompute(groupsX,groupsY,1);
        glFinish();

        //measure average of 10 runs
        struct timespec t0,t1;
        clock_gettime(CLOCK_MONOTONIC,&t0);
        for(int i=0; i < 10; i++)glDispatchCompute(groupsX,groupsY,1);
        glFinish();
        clock_gettime(CLOCK_MONOTONIC,&t1);

        timings[v]=((t1.tv_sec - t0.tv_sec)* 1e6+(t1.tv_nsec - t0.tv_nsec)/ 1e3)/ 10.0;
    }

    //activate the fastest found variant
    int best=-1;
    for(int i=0; i < 3; i++){
        if(variants[i].prog != 0 &&(best < 0 || timings[i] < timings[best]))best=i;
    }
    if(best >= 0)selectActiveSobelVariant(variants[best].prog,variants[best].wgX,variants[best].wgY);

    return env->NewStringUTF("bench done");
}
