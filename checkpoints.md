**Accelerated Mobile Video Processing System**
Detailed Development Plan & Checklist
Team: Salil Gujar (2025MCS2106) & Nitish Kumar (2025MCS2100)

Part 1: Core Development Phases
This project is structured into four major phases, moving from a foundational baseline up through advanced heterogeneous computing optimizations.

**Phase 1: Camera Pipeline and Baseline System**
Goal: Establish a working, end-to-end camera-to-screen pipeline featuring a scalar CPU filter and a live performance dashboard.

Stage 1 — Project Setup and Toolchain: We'll start by creating the Android project (targeting API 26+) and enabling the NDK to compile our native C/C++ libraries via JNI. We will secure the necessary camera and storage permissions, verify that our build/run cycle works on a physical device, and initialize version control.

Stage 2 — Camera2 Pipeline Setup:  Next, we'll tap into the rear-facing camera using CameraManager for a continuous 30 FPS preview. We will configure an ImageReader to capture 1280×720 frames in YUV_420_888 format on a background thread, logging timestamps to ensure steady frame delivery.

Stage 3 — YUV to RGBA Conversion: In the native JNI layer, we'll extract the Y, U, and V planes and implement a standard BT.601 scalar conversion to RGBA. We'll pass this buffer back to the Android UI to verify that the colors and orientation are visually correct, establishing our first baseline latency metric.

Stage 4 — Baseline Filter Implementation: We will write a standard 5×5 Gaussian blur in C using nested loops. This will process the R, G, and B channels independently using normalized weights while handling border padding. This non-accelerated scalar version acts as our absolute correctness reference for all future optimizations.

Stage 5 — SurfaceView Rendering: Once processed natively, we will convert the byte buffer into an Android Bitmap and push it to a SurfaceView or TextureView on the UI thread, achieving a live, filtered camera feed.

Stage 6 — Initial Performance Dashboard: We'll overlay a live dashboard onto the camera feed. This early version will track the active execution mode (Baseline), calculate a rolling FPS average, and measure both frame processing latency (inside JNI) and end-to-end latency (capture to screen).

**Phase 2: SIMD-Accelerated Frame Processing**
Goal: Replace the scalar filter with an ARM NEON vectorized path to prove correctness and demonstrate a measurable speedup.

Stage 1 — NEON Intrinsics Warm-up:  Before tackling the complex Gaussian blur, we'll write a simple grayscale converter using uint8x16_t NEON vectors to process 16 pixels at once. This lets us verify byte-for-byte correctness against a scalar reference and understand NEON throughput limits.

Stage 2 — NEON Gaussian Filter (Separable Decomposition): We'll optimize the Gaussian blur by splitting it into horizontal and vertical passes, drastically reducing the math required. Using NEON intrinsics, we'll load chunks of memory, apply weights, accumulate the results, and carefully match the border handling of our baseline.

Stage 3 — Correctness Verification: We will pit the scalar and NEON paths against each other on a static test frame. A native memory comparison will check for exact matches (allowing a tiny ±1 tolerance for integer arithmetic rounding). Passing this check is mandatory to activate SIMD mode.

Stage 4 — Runtime Mode Switching: We'll add a UI toggle to let the user switch between Baseline and SIMD modes on the fly. The JNI layer will seamlessly route the next incoming frame to the chosen path without requiring an app restart.

Stage 5 — Dashboard Updates for SIMD: The dashboard will be expanded to show the active SIMD status, compare per-frame latencies, and display a calculated speedup ratio (e.g., "3.2× faster") compared to the baseline.

**Phase 3: GPU-Accelerated Processing and Hybrid Mode**
Goal: Introduce a GPU compute shader path, implement a hybrid CPU/GPU split, and enable seamless runtime switching across all four modes.

Stage 1 — OpenGL ES Context Setup: We will spin up a headless EGL context on the processing thread to handle GPU computations, verifying the setup with a simple pass-through compute shader and mastering Shader Storage Buffer Objects (SSBOs) for data transfer.

Stage 2 — GPU Gaussian Filter via Compute Shader: We'll write a compute shader where each invocation handles a single pixel, mapping the RGBA frame to the GPU via SSBOs. We will manage the workgroups and memory barriers to ensure the data is safely read back to the CPU after processing.

Stage 3 — GPU Correctness Verification: Similar to the SIMD verification, we'll compare the GPU output against the baseline, allowing a slight ±2 tolerance due to floating-point arithmetic differences in the shader, and log the success to the dashboard.

Stage 4 — Hybrid Mode Implementation:  We'll split the incoming 720p frame horizontally. The top half will route to the NEON CPU thread, while the bottom half dispatches to the GPU concurrently. Once both finish, we'll stitch the buffers back together.

Stage 5 — Full Runtime Mode Switching: The UI toggle will be updated to handle all four modes (Baseline, SIMD, GPU, Hybrid). We will stress-test the transitions to ensure zero dropped frames or visual artifacts during rapid switching.

Stage 6 — Sustained Load Testing: We'll run each mode for an extended period, actively monitoring frame time spikes (jitter) and polling the system for thermal throttling to ensure the app remains interactive under heavy load.

Stage 7 — Dashboard Updates for GPU/Hybrid: The overlay will now feature active/idle indicators for both SIMD and GPU, display the Hybrid mode's row-split ratio, and introduce a thermal proxy (°C) and jitter event counter.

**Phase 4: End-to-End Optimization and Finalization**
Goal: Deliver a polished, stable, and highly optimized system ready for live presentation.

Stage 1 — Pipeline Latency Profiling: We will instrument the entire pipeline with nanosecond timestamps to isolate exactly where the bottlenecks are—from capture to conversion, processing, and rendering.

Stage 2 — Memory and Buffer Optimization: We will eliminate garbage collection pauses by reusing pre-allocated Bitmaps, intermediate row buffers, and GPU SSBOs rather than creating new ones every frame.

Stage 3 — NEON Filter Tuning: Using the Android Studio CPU profiler, we'll ensure our loops are cleanly vectorized and use memory prefetching to minimize cache-miss stalls on the CPU.

Stage 4 — GPU Shader Optimization: We will experiment with different workgroup sizes and move our static kernel weights into a uniform block to speed up GPU memory access.

Stage 5 — Hybrid Split Ratio Tuning: Instead of a static 50/50 split, we will calculate the split dynamically based on the real-time throughput of the NEON and GPU paths, ensuring both finish their workload at the exact same time.

Stage 6 — Final Dashboard Polish: We will finalize the UI layout for maximum readability, organizing metrics into clear logical blocks and using color coding to distinguish the active execution modes.

Stage 7 — Final Integration and Stability Run: The entire system will undergo a rigorous 30-minute uninterrupted run, cycling through all modes to ensure absolute stability, zero crashes, and sustained interactive frame rates before the final demonstration.
