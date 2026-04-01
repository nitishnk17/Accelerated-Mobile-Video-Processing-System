cmake_minimum_required(VERSION 3.22.1)

project("csproject")

# compile native-lib.cpp into libcsproject.so
add_library(csproject SHARED native-lib.cpp)

# force -O2 even in debug builds
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O2")

# align ELF LOAD segments to 16 KB for Android 15+ device compatibility
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

# locate android's built-in libraries
find_library(log-lib log)
find_library(EGL-lib EGL)      # phase 3 stage 1 — EGL context management
find_library(GLESv3-lib GLESv3) # phase 3 stage 1 — OpenGL ES 3.1 compute shaders

# link liblog.so, libEGL.so, and libGLESv3.so
target_link_libraries(csproject
    ${log-lib}
    ${EGL-lib}
    ${GLESv3-lib}
)
