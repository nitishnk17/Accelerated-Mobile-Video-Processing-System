cmake_minimum_required(VERSION 3.22.1)

project("csproject")

# compile native-lib.cpp into libcsproject.so
add_library(csproject SHARED native-lib.cpp)

# locate android's built-in log library
find_library(log-lib log)

# link liblog.so so we can call __android_log_print
target_link_libraries(csproject ${log-lib})
