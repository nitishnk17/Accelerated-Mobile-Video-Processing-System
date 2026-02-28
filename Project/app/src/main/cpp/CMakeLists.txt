#CMakeLists.txt — Native (C/C++) Build Configuration
#CMake is a cross-platform C/C++ build tool. The Android NDK uses this file to know which C++ source files to compile and how to link them into a
#native shared library (.so file) that gets bundled inside the APK

cmake_minimum_required(VERSION 3.22.1)

project("csproject")

#add_library() compiles C++ source files into a native library
#csproject -> output library name. Android wraps this into libcsproject
#SHARED -> build a shared library that is loaded at app runtime
#native-lib.cpp -> our C++ source file to compile
add_library(
    csproject
    SHARED
    native-lib.cpp
)

#find_library() locates a system library that is pre-installed on every Android device
#
#log-lib the CMake variable that will hold the full path to the found library
#log -> the short name of the Android system log library -> resolves to liblog.so
find_library(log-lib log)

#target_link_libraries() tells the linker which libraries our native library depends on Withoutthis  linking liblog.so here the build would fail with
#undefined reference to __android_log_print" when our C++ code tries to call it.
#csproject -> our library (defined above in add_library)
# ${log-lib}-> expands to the full path of liblog.so found by find_library above
target_link_libraries(
    csproject
    ${log-lib}
)
