cmake_minimum_required(VERSION 3.22.1)
project("vmouse_native")

find_library(log-lib log)

# Standalone executable with .so naming so AGP automatically bundles it into APK lib/<abi>/
add_executable(vmouse_bin vmouse_daemon.c)
set_target_properties(vmouse_bin PROPERTIES PREFIX "lib" SUFFIX ".so")
target_link_libraries(vmouse_bin ${log-lib})

# Also build standard shared library
add_library(vmouse SHARED vmouse_daemon.c)
target_link_libraries(vmouse ${log-lib})

# Plain named executable
add_executable(vmouse_daemon vmouse_daemon.c)
target_link_libraries(vmouse_daemon ${log-lib})
