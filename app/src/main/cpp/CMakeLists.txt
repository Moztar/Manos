cmake_minimum_required(VERSION 3.22.1)
project("monos")

# Add native shared library for PRoot interactions and graphics composition
add_library(
        monos_native
        SHARED
        proot_emulation.cpp
        graphics_renderer.cpp
)

# Find Android logging library
find_library(
        log-lib
        log
)

# Link libraries (including GLES, EGL, and Android Native Window)
target_link_libraries(
        monos_native
        ${log-lib}
        GLESv2
        EGL
        android
)
