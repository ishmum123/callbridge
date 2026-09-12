cmake_minimum_required(VERSION 3.22.1)

project("callbridge_native")

# M1b (spec §4.3, §7): adds the Route A research probe (audioclient_probe.cpp — deliberately a
# probe, not a full native AudioTrack construction; see that file's header for why) and the
# ALSA control-interface mixer helper (alsa_mixer.cpp) backing MixerControl.kt. Both talk to
# the kernel/linker directly (ioctl, dlopen/dlsym) rather than vendoring a third-party library,
# so this target has no external dependencies (no network fetch at build time).
add_library(callbridge_native SHARED
    native.cpp
    audioclient_probe.cpp
    alsa_mixer.cpp
)

find_library(log-lib log)
find_library(dl-lib dl)

target_link_libraries(callbridge_native
    ${log-lib}
    ${dl-lib}
)
