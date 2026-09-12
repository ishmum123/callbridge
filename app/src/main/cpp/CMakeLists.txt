cmake_minimum_required(VERSION 3.22.1)

project("callbridge_native")

# Stub native library for M0. Later milestones (M1b injection) add the
# AudioTrack/AudioRecord HAL shims, VAD, and resampler sources here.
add_library(callbridge_native SHARED
    native.cpp
)

find_library(log-lib log)

target_link_libraries(callbridge_native
    ${log-lib}
)
