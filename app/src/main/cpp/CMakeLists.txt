cmake_minimum_required(VERSION 3.22.1)
project(mgba_android C CXX)

# Enforce language standards
set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Configure mGBA options strictly BEFORE add_subdirectory
set(M_CORE_GBA ON CACHE BOOL "Build Game Boy Advance core" FORCE)
set(M_CORE_GB ON CACHE BOOL "Build Game Boy / Game Boy Color core" FORCE)

# Disable desktop frontends and unneeded components
set(BUILD_QT OFF CACHE BOOL "Build Qt frontend" FORCE)
set(BUILD_SDL OFF CACHE BOOL "Build SDL frontend" FORCE)
set(BUILD_GL OFF CACHE BOOL "Build with desktop OpenGL" FORCE)
set(BUILD_GLES2 OFF CACHE BOOL "Build with OpenGLES 2" FORCE)
set(BUILD_GLES3 OFF CACHE BOOL "Build with OpenGLES 3" FORCE)
set(USE_DISCORD_RPC OFF CACHE BOOL "Build Discord RPC" FORCE)
set(ENABLE_SCRIPTING OFF CACHE BOOL "Scripting support" FORCE)
set(USE_LUA OFF CACHE BOOL "Lua support" FORCE)
set(USE_EDITLINE OFF CACHE BOOL "CLI debugger" FORCE)
set(USE_GDB_STUB OFF CACHE BOOL "GDB stub" FORCE)
set(USE_FFMPEG OFF CACHE BOOL "FFmpeg support" FORCE)
set(USE_SQLITE3 OFF CACHE BOOL "SQLite3 support" FORCE)
set(USE_ELF OFF CACHE BOOL "ELF support" FORCE)
set(USE_LIBZIP OFF CACHE BOOL "libzip support" FORCE)

# Enable required compression engines
set(USE_LZMA ON CACHE BOOL "7-Zip LZMA support" FORCE)
set(USE_ZLIB ON CACHE BOOL "zlib support" FORCE)

# Disable test and profiling suites
set(BUILD_SUITE OFF CACHE BOOL "Build test suite" FORCE)
set(BUILD_TEST OFF CACHE BOOL "Build test harness" FORCE)
set(BUILD_PERF OFF CACHE BOOL "Build perf profiling tool" FORCE)
set(BUILD_EXAMPLE OFF CACHE BOOL "Build example frontends" FORCE)

# Library build mode: shared library libmgba.so
set(BUILD_SHARED ON CACHE BOOL "Build shared library" FORCE)
set(BUILD_STATIC OFF CACHE BOOL "Build static library" FORCE)
set(SKIP_GIT ON CACHE BOOL "Skip git version query" FORCE)

# Add upstream mGBA core subdirectory
add_subdirectory(mgba)

# Define JNI Bridge shared library with video renderer and audio engine
add_library(mgba_jni SHARED
    mgba_jni.c
    video/egl_renderer.cpp
    audio/oboe_audio.cpp
)

# Exported and internal headers
target_include_directories(mgba_jni PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/video
    ${CMAKE_CURRENT_SOURCE_DIR}/audio
    ${CMAKE_CURRENT_SOURCE_DIR}/mgba/include
    ${CMAKE_CURRENT_BINARY_DIR}/mgba/include
)

# Find Android AAudio native library
find_library(AAUDIO_LIB aaudio)

# Link core dependencies & graphics/audio APIs
target_link_libraries(mgba_jni
    mgba
    android
    log
    z
    m
    EGL
    GLESv3
    GLESv2
    ${AAUDIO_LIB}
)
