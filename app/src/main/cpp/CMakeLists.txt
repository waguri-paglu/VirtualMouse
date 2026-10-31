cmake_minimum_required(VERSION 3.22.1)
project("vmouse_native")

find_library(log-lib log)

# 1. Root uinput kernel daemon executable (packaged as libvmouse_bin.so in APK lib/<abi>/)
add_executable(vmouse_bin vmouse_daemon.c)
set_target_properties(vmouse_bin PROPERTIES PREFIX "lib" SUFFIX ".so")
target_link_libraries(vmouse_bin ${log-lib})

# 2. Ultra-fast JNI client library loaded directly by the Android app
add_library(vmouse SHARED vmouse_jni.c)
target_link_libraries(vmouse ${log-lib})
