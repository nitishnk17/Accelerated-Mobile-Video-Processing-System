# Accelerate: Accelerated Mobile Video Processing System

We built **Accelerate**, an Android application designed to push the limits of mobile video processing by squeezing every bit of performance out of the CPU and GPU.

## What is this?

Ever wondered how your phone handles complex video filters in real-time? **Accelerate** is a deep dive into that world. It's a real-time camera app that performs **Sobel Edge Detection** at 30+ FPS, but with a twist—it allows you to switch between different "engines" on the fly to see how much faster (or slower!) different optimization techniques are.

### The Engines:
*   ** Base (Scalar):** Our "ground truth." It uses standard C++ loops. It's slow, but it's the reference we use to make sure our optimizations don't break anything.
*   ** SIMD (NEON):** Now we're talking. This engine uses ARM NEON intrinsics to process 16 pixels at once using the CPU's vector registers.
*   ** GPU (Compute Shader):** This takes the workload off the CPU entirely. We wrote OpenGL ES 3.1 Compute Shaders to parallelize the edge detection across hundreds of GPU cores.
*   ** Hybrid (CPU + GPU):** The "smart" mode. It splits the frame in half and lets the CPU and GPU work together simultaneously. It even balances the load dynamically based on which one is faster at that moment!
*   ** Raw:** Just the camera feed, for when you want to see the world without edges.

---

##  How we built it

We didn't just want a fast app; we wanted a *transparent* one. The app features a high-precision **Performance Dashboard** that breaks down exactly where every nanosecond is going—from camera capture to YUV conversion, filter processing, and finally rendering to your screen.

### Technical Stack:
*   **UI:** Kotlin & Jetpack Compose (Modern, reactive, and fun to work with).
*   **Native Layer:** C++ (JNI/NDK) for the heavy lifting.
*   **CPU Optimization:** ARM NEON (Vectorization).
*   **GPU Optimization:** OpenGL ES 3.1 & EGL (Headless compute contexts).
*   **Algorithms:** BT.601 YUV-to-RGBA conversion and 3x3 Sobel kernels.

---

##  Key Features

*   **Real-time mode switching:** Swap between Scalar, NEON, and GPU modes without a single dropped frame.
*   **Dynamic Load Balancing:** In Hybrid mode, the app monitors the temperature and speed of both the CPU and GPU, adjusting the "split line" so they finish their tasks at the exact same time.
*   **On-device Benchmarking:** On startup, the app "races" different GPU workgroup sizes (8x8, 16x16, 32x4) to find the sweet spot for *your* specific phone.
*   **Pipelined Readback:** We use a double-buffered SSBO pipeline for the GPU, meaning the CPU doesn't have to wait for the GPU to finish before starting the next frame.
*   **Correctness Verification:** Built-in "Check" modes that compare optimized outputs against the scalar reference byte-for-byte. If we're fast, we're also accurate.

---

##  Building and Running

1.  Open the `Project` folder in **Android Studio**.
2.  Make sure you have the **NDK** and **CMake** installed (via SDK Manager).
3.  Connect an Android device (Physical device is highly recommended; emulators usually don't support the full range of NEON/GPU optimizations).
4.  Hit **Run**!

---

##  The Team

We're two grad students at IIT Delhi who spent way too many nights debugging memory barriers and cache misses.

*   **Salil Gujar** (2025MCS2106)
*   **Nitish Kumar** (2025MCS2100)

