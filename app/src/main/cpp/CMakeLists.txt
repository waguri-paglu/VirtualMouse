cmake_minimum_required(VERSION 3.22.1)
project("vmouse_native")

add_library(vmouse SHARED
    vmouse_daemon.c
)

find_library(log-lib log)
target_link_libraries(vmouse ${log-lib})

add_executable(vmouse_daemon
    vmouse_daemon.c
)
target_link_libraries(vmouse_daemon ${log-lib})
