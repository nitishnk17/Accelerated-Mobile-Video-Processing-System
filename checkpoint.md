# Accelerated Mobile Video Processing System

**Course Project — M.Tech Computer Science**
Indian Institute of Technology, Delhi

| | |
|---|---|
| **Authors** | Salil Gujar (2025MCS2106) · Nitish Kumar (2025MCS2100) |
| **Document** | Sections 6 & 7 — Development Plan and Demo Requirements |
| **Target Device** | Physical Android device, API 26+, ARM64 |
| **Project Duration** | 80 days |

---

## Table of Contents

1. [Section 6 — 80-Day Development Plan](#section-6)
   - [6.1 Day 20 — Camera Pipeline and Baseline System](#61-day-20)
   - [6.2 Day 40 — SIMD-Accelerated Frame Processing](#62-day-40)
   - [6.3 Day 70 — GPU-Accelerated Processing and Hybrid Mode](#63-day-70)
   - [6.4 Day 80 — Optimization and Finalization](#64-day-80)
2. [Section 7 — Final Demonstration Requirements](#section-7)

---

## Section 6 — 80-Day Development Plan

### Overview

The project is structured around four progressive checkpoints. Each one delivers a working, demonstrable system — not just code — and each builds directly on what the previous one established. The table below summarizes the milestones.

| Checkpoint | Day | Deliverable |
|---|---|---|
| Baseline System | 20 | Camera pipeline + scalar Gaussian blur + live dashboard |
| SIMD Acceleration | 40 | ARM NEON vectorized filter with runtime mode switching |
| GPU + Hybrid | 70 | OpenGL ES 3.1 compute shader + concurrent CPU/GPU hybrid mode |
| Final System | 80 | Fully optimized, stable, demo-ready system |

---

### 6.1 Day 20 — Camera Pipeline and Baseline System

**Objective:** A complete, working camera-to-screen pipeline that captures live frames, applies a scalar Gaussian blur in native code, and displays a real-time performance dashboard.

#### Stage 1 — Project Setup and Toolchain `Days 1–4`

The first stage is entirely about laying a reliable foundation. An Android project is created with `minSdkVersion 26`, the NDK is enabled in `build.gradle`, and `CMakeLists.txt` is configured to build a native `.so` via JNI. Camera and storage permissions are declared in `AndroidManifest.xml`. The toolchain is verified end-to-end by compiling a trivial `native_hello()` JNI function and running it on the physical device. Version control is initialized before any real code is written.

#### Stage 2 — Camera2 Pipeline Setup `Days 5–9`

The rear-facing camera is opened via `CameraManager.openCamera()` with a `CaptureRequest` configured for continuous preview at a fixed 30 fps (`CONTROL_AF_MODE_CONTINUOUS_VIDEO`). Frames are delivered in `YUV_420_888` format at **1280×720** through an `ImageReader`. An `OnImageAvailableListener` processes each frame on a dedicated background `HandlerThread`. Frame timestamps are logged throughout, and steady delivery with no drops under idle conditions is confirmed before moving on.

#### Stage 3 — YUV to RGBA Conversion `Days 10–12`

In JNI/C, the Y, U, and V planes and strides are extracted from the `YUV_420_888` image and converted to RGBA per pixel using the standard BT.601 formula. The result is returned to the Java/Kotlin rendering layer. Output is verified visually for correct color and orientation, and conversion time per frame is logged — this is the first baseline latency measurement in the project.

#### Stage 4 — Baseline Filter Implementation `Days 13–16`

A **5×5 Gaussian blur** is implemented in C as nested loops over the RGBA buffer. The kernel uses precomputed float weights normalized to 1.0. R, G, and B channels are processed independently; the alpha channel passes through unchanged. Border pixels use clamp-to-edge padding. This scalar implementation is intentionally simple. Its sole purpose is to be provably correct — it will serve as the reference that all future accelerated paths are verified against.

#### Stage 5 — SurfaceView Rendering `Days 17–18`

The processed RGBA buffer is converted to an Android `Bitmap` via `Bitmap.copyPixelsFromBuffer()` and drawn to a `SurfaceView` using a `Canvas`. Rendering runs on the UI thread through the standard `lockCanvas()` / `unlockCanvasAndPost()` cycle. The filtered live camera feed is confirmed to be visible on screen in real time before moving to the dashboard.

#### Stage 6 — Day 20 Dashboard `Days 19–20`

A semi-transparent overlay is drawn on each frame displaying the following metrics:

| Metric | Source |
|---|---|
| Mode | `BASELINE` (static at this stage) |
| FPS | Rolling average over last 30 frames |
| Frame processing latency | JNI call entry to return, nanosecond clock |
| End-to-end latency | `ImageReader` callback to `unlockCanvasAndPost()` |

The dashboard updates every rendered frame.

> **Day 20 Checkpoint:** The device displays a live blurred camera feed with a performance overlay showing approximately 15–25 FPS and measured latency values.

---

### 6.2 Day 40 — SIMD-Accelerated Frame Processing

**Objective:** Replace the scalar Gaussian filter with an ARM NEON vectorized implementation. Verify that output is numerically equivalent to the baseline, expose a runtime mode switch in the UI, and show a measurable speedup on the dashboard.

#### Stage 1 — NEON Intrinsics Warm-up `Days 21–25`

Before modifying the main filter, a standalone NEON grayscale conversion is written as a learning exercise. It processes 16 pixels per iteration using `vld1q_u8`, computes a weighted luminance sum via `vmull_u8` and a right-shift, and stores results with `vst1q_u8`. Output is confirmed byte-for-byte identical to the scalar grayscale on a test buffer. Throughput on a 1280×720 buffer is measured against the scalar equivalent. This exercise builds real intuition for NEON before committing to the Gaussian path.

#### Stage 2 — NEON Gaussian Filter — Separable Decomposition `Days 26–30`

The 5×5 Gaussian is decomposed into two 1D passes — horizontal then vertical — reducing per-pixel multiply operations from 25 to 10. The horizontal pass is implemented with NEON as follows:

- `vld1q_u8` loads 16-byte chunks
- `vextq_u8` constructs shifted neighbor views for kernel access
- `vmull_u8` and `vmlal_u8` handle weighted accumulation
- `vshrn_n_u16` narrows back to `uint8x8_t`

The vertical pass follows the same structure using a row pointer array. Border handling matches the scalar reference exactly so that correctness comparison is unambiguous.

#### Stage 3 — Correctness Verification `Days 31–33`

Both scalar and NEON paths are run on the same static test frame. Outputs are compared byte-by-byte in JNI using `memcmp()`, with a tolerance of ±1 per channel to account for rounding differences in integer NEON arithmetic. A PASS/FAIL indicator is displayed on the dashboard, and SIMD mode is only made available once verification passes. This gate remains permanent throughout the project.

#### Stage 4 — Runtime Mode Switching `Days 34–36`

A `Button` or `Spinner` is added to the UI with options `[BASELINE, SIMD]`. The selected mode is passed as an integer to the JNI processing function, which dispatches to the appropriate path via a `switch` statement. The transition takes effect on the next incoming frame — no app restart is required.

#### Stage 5 — Day 40 Dashboard Update `Days 37–40`

The overlay is extended with the following additions:

| New Field | Description |
|---|---|
| SIMD active indicator | Green (active) / Red (inactive) |
| Per-frame latency | Updated per mode |
| Speedup ratio | `baseline_latency / simd_latency`, e.g., `3.2×` |

A benchmark table of baseline vs. SIMD latency across 100 consecutive frames is also captured and logged.

> **Day 40 Checkpoint:** The dashboard shows SIMD delivering a 3–6× reduction in per-frame processing latency compared to baseline. Visual output is identical in both modes.

---

### 6.3 Day 70 — GPU-Accelerated Processing and Hybrid Mode

**Objective:** Introduce a GPU execution path via an OpenGL ES 3.1 compute shader and a hybrid mode that concurrently processes each frame across both CPU (NEON) and GPU. All four modes are available with seamless runtime switching.

#### Stage 1 — OpenGL ES Context Setup `Days 41–46`

An EGL context is initialized on the processing thread (`EGL_OPENGL_ES3_BIT`) with an offscreen `EGL_PBUFFER_BIT` surface for headless GPU computation. Context creation is verified and the `GL_VERSION` string is logged. A trivial pass-through compute shader (`#version 310 es`) is written and compiled as a proof-of-concept. SSBO (Shader Storage Buffer Object) creation, binding, and memory synchronization patterns are studied and understood before the actual filter is written.

#### Stage 2 — GPU Gaussian Filter via Compute Shader `Days 47–55`

The RGBA frame is uploaded to the GPU as an SSBO:

```c
glGenBuffers(1, &ssbo);
glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssbo);
glBufferData(GL_SHADER_STORAGE_BUFFER, size, data, GL_DYNAMIC_COPY);
```

The compute shader assigns one invocation per pixel in 16×16 work groups. Each invocation reads a 5×5 neighborhood from the input SSBO, applies Gaussian weights, and writes its result to the output SSBO. Image borders are handled with conditional clamping in the shader. The dispatch is:

```c
glDispatchCompute(width / 16, height / 16, 1);
glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
```

Results are read back to CPU via `glGetBufferSubData()`.

#### Stage 3 — GPU Correctness Verification `Days 56–58`

The scalar baseline and GPU path are run on the same static frame and compared in JNI. A tolerance of ±2 per channel is used to account for floating-point arithmetic in the shader. GPU correctness status is shown on the dashboard. End-to-end GPU submission latency — from `glDispatchCompute()` to readback complete — is profiled and recorded.

#### Stage 4 — Hybrid Mode Implementation `Days 59–63`

The 1280×720 frame is split horizontally at a configurable split row (default: row 360). The top half is sent to the NEON filter on a CPU `pthread`; the bottom half is dispatched to the GPU shader concurrently. Completion is synchronized via `pthread_join()` for the NEON thread and `glFinish()` on the GL thread. The two output halves are stitched with `memcpy()`. The dashboard shows the current split ratio (e.g., `50% CPU / 50% GPU`).

#### Stage 5 — All Four Modes with Runtime Switching `Days 64–67`

The mode selector is extended to `[BASELINE, SIMD, GPU, HYBRID]`. Switching between any two modes is seamless — no dropped frames, no visual artifacts, no restart. As a robustness check, all four modes are cycled rapidly for 5 minutes to confirm there are no resource leaks or race conditions under rapid transitions.

#### Stage 6 — Sustained Load Testing `Days 68–69`

Each mode is run continuously for 10 minutes. FPS and per-frame latency are logged to a CSV every second. Thermal state is monitored by polling `/sys/class/thermal/thermal_zone*/temp` from JNI. Frame time spikes exceeding 2× the rolling median are flagged as jitter events. All modes must maintain above 20 FPS throughout.

#### Stage 7 — Day 70 Dashboard Update `Day 70`

The overlay now shows the full picture:

| Field | Description |
|---|---|
| Mode | `BASELINE / SIMD / GPU / HYBRID` |
| FPS + frame latency | For the current mode |
| SIMD indicator | Active / idle |
| GPU indicator | Green when a shader is dispatched |
| Hybrid split ratio | e.g., `360 rows CPU / 360 rows GPU` |
| Thermal proxy | CPU temperature in °C |
| Jitter events | Count of spikes in the last 60 seconds |

> **Day 70 Checkpoint:** All four modes run live. The dashboard clearly differentiates which hardware resources are active and by how much. GPU and Hybrid show measurable improvement over Baseline.

---

### 6.4 Day 80 — Optimization and Finalization

**Objective:** A polished, demo-ready system with optimal performance, zero unnecessary allocations in the hot path, an adaptive hybrid split ratio, and a final dashboard suitable for live presentation.

#### Stage 1 — Pipeline Latency Profiling `Days 71–73`

Nanosecond timestamps are inserted at four points in the pipeline:

| # | Measurement Point |
|---|---|
| 1 | Camera frame arrival — `ImageReader` callback |
| 2 | YUV→RGBA conversion complete |
| 3 | Filter processing complete (per mode) |
| 4 | `unlockCanvasAndPost()` complete |

End-to-end latency is defined as point (4) minus point (1) for every frame. The dominant latency contributor is identified for each mode and documented.

#### Stage 2 — Memory and Buffer Optimization `Days 74–75`

Three hot-path allocations are eliminated:

- **Bitmap:** `Bitmap.createBitmap()` is replaced with a pre-allocated reusable `Bitmap`, updated in-place via `copyPixelsFromBuffer()`
- **SSBO:** Pre-allocated at session start; reused per frame with `glBufferSubData()` instead of `glBufferData()`
- **NEON buffers:** Intermediate row buffers pre-allocated; `malloc` removed from the filter inner loop

Latency profiling is re-run after each change to confirm measurable reduction in GC pauses and allocation overhead.

#### Stage 3 — NEON Filter Tuning `Days 75–76`

The NEON path is profiled with the Android Studio CPU profiler. Compiler output is inspected to confirm the loop body contains `vld1q_u8` and `vst1q_u8` — evidence of clean auto-vectorization. `__builtin_prefetch()` is added for next-row data to reduce cache-miss stalls. Final NEON latency and speedup ratio are measured and documented.

#### Stage 4 — GPU Shader Optimization `Days 76–77`

Work group sizes are experimented with — `local_size_x=32, local_size_y=8` is a natural candidate for better memory access patterns on ARM GPUs. Gaussian kernel weights are moved into a `uniform` block to avoid repeated SSBO reads per invocation. Switching from SSBOs to `imageLoad/imageStore` with `layout(rgba8) image2D` is evaluated as a texture-cache-friendly alternative. Results are measured and documented.

#### Stage 5 — Hybrid Split Ratio Tuning `Day 78`

Rather than a fixed 50/50 split, the split row is computed dynamically based on measured throughput:

```
split_row = (NEON_throughput / (NEON_throughput + GPU_throughput)) × height
```

This balances both execution paths to finish at approximately the same time, eliminating the idle wait before stitching. Adaptive splitting is verified to produce lower end-to-end latency than the fixed split.

#### Stage 6 — Final Dashboard Polish `Day 79`

The dashboard is finalized with live-updating values and color-coded mode labels:

```
┌─────────────────────────────────────────────┐
│  MODE: [HYBRID]         FPS: 42.3           │
│  Frame latency:  18.4 ms                    │
│  End-to-end:     22.1 ms                    │
├─────────────────────────────────────────────┤
│  SIMD  [████████████████░░░░]  ACTIVE       │
│  GPU   [████████░░░░░░░░░░░░]  ACTIVE       │
│  Split: 360 rows CPU / 360 rows GPU         │
├─────────────────────────────────────────────┤
│  Baseline: 84.2 ms   Speedup: 4.6×          │
│  Thermal: 38°C        Jitter events: 0      │
└─────────────────────────────────────────────┘
```

Mode label colors: `grey` → Baseline · `blue` → SIMD · `green` → GPU · `yellow` → Hybrid

#### Stage 7 — Final Integration and Stability Run `Day 80`

The complete system runs for 30 uninterrupted minutes on the physical device. Modes are cycled every 5 minutes. FPS, latency, and thermal readings are logged to file throughout. Acceptance criteria: no crashes, no ANR dialogs, no visual corruption, and FPS consistently above 20 in all modes for the full duration.

> **Day 80 Checkpoint:** The complete system — all four modes, full dashboard, adaptive hybrid split — is running stably and ready for live presentation.

---

## Section 7 — Final Demonstration Requirements

The final demonstration must satisfy all requirements listed below. Each requirement is directly verifiable against the checkpoint outcomes from Section 6.

---

### 7.1 Hardware and Environment

| Requirement | Detail |
|---|---|
| Device | Physical Android device, API 26+, ARM64 |
| Network | Airplane mode — no Wi-Fi, no mobile data |
| Logcat | Running internally; not visible to the audience |
| Battery | Above 50% at demo start |
| Brightness | Visible, but not at a throttling level |

---

### 7.2 Live Camera and Continuous Output

The camera runs at **1280×720** in `YUV_420_888` format, targeting 30 fps. Processed output is on screen at all times — there is no manual frame-advance step. The Gaussian blur effect must be visually apparent; the output should look clearly different from an unfiltered camera preview. Frame delivery runs uninterrupted for the entire demo period.

---

### 7.3 Dashboard Visible During Execution

The performance overlay is visible at all times and displays the following fields live:

| Field | Requirement |
|---|---|
| Current mode | Updates instantly on each mode switch |
| FPS | Rolling average, updated every frame |
| Per-frame processing latency | In milliseconds, for the current mode |
| End-to-end latency | Camera capture to display, in ms |
| SIMD indicator | Active/inactive — reflects actual execution |
| GPU indicator | Active/inactive — reflects actual dispatch |
| Thermal proxy | CPU temperature in °C |
| Speedup vs. baseline | Numerical ratio shown for all non-baseline modes |

---

### 7.4 Mode Switching Without Restart

All four modes — **Baseline → SIMD → GPU → Hybrid** — are demonstrated live in a single session. Each transition takes effect within one frame of the button press. There is no black screen, crash, or app restart between switches.

---

### 7.5 Quantitative Performance Claims

The dashboard must show measurable, readable numbers that support the following claims without any post-processing:

| Comparison | Required Improvement |
|---|---|
| SIMD vs. Baseline | At least **3× reduction** in per-frame processing latency |
| GPU vs. Baseline | At least **2× reduction** in per-frame processing latency |
| Hybrid vs. Baseline | Latency between SIMD and GPU best cases; both resources active |

---

### 7.6 Correctness Attestation

A PASS / FAIL correctness flag is visible on the dashboard for both SIMD and GPU modes. The flag is set once at session start by running each accelerated path against the scalar baseline on a known static reference frame. **The demo does not proceed until both flags show PASS.** This is not cosmetic — it is the only honest basis for claiming that acceleration produces correct results.

---

### 7.7 Sustained Stability

| Requirement | Threshold |
|---|---|
| Minimum run time | 15 minutes without crash, ANR, or intervention |
| Minimum FPS | 20 FPS for all modes throughout the demo |
| Thermal throttling | Logged on dashboard if it occurs; system must remain functional |

---

### 7.8 Demo Narrative Flow

The demonstration follows a structured sequence designed to make the performance argument observable step by step:

| Step | Action | What to Observe |
|---|---|---|
| 1 | Launch in Baseline mode | Note FPS and latency — this is the reference |
| 2 | Switch to SIMD | FPS rises, latency drops; speedup ratio appears |
| 3 | Switch to GPU | GPU indicator activates; compare latency to SIMD |
| 4 | Switch to Hybrid | Both indicators active; note split ratio and end-to-end latency |
| 5 | Leave Hybrid running for 5 min | FPS holds steady; thermal reading stays stable |
| 6 | Switch back to Baseline | FPS visibly drops — confirms acceleration was real, not an artifact |
