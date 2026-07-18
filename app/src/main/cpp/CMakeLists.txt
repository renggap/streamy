cmake_minimum_required(VERSION 3.22)
project(streamy_lame)

# encoder-only libmp3lame (decoder + mpglib disabled at host configure time via
# --disable-decoder; config.h has HAVE_MPGLIB undef'd, so the mpglib_interface.c
# translation unit is empty and is intentionally NOT vendored).
file(GLOB LAME_SOURCES "${CMAKE_CURRENT_SOURCE_DIR}/lame/libmp3lame/*.c")

add_library(mp3lame_static STATIC ${LAME_SOURCES})
target_include_directories(mp3lame_static PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR}/lame/libmp3lame
        ${CMAKE_CURRENT_SOURCE_DIR}/lame/include)

# LAME sources gate config.h inclusion on -DHAVE_CONFIG_H (matches the autoconf Makefile).
# config.h defines STDC_HEADERS, the ieee754_float32_t typedef, etc.
target_compile_definitions(mp3lame_static PRIVATE HAVE_CONFIG_H)
# LAME generates a lot of inline-related noise; keep warnings off so the build log stays readable.
target_compile_options(mp3lame_static PRIVATE -w)

add_library(streamy_lame SHARED lame_jni.cpp)
target_link_libraries(streamy_lame mp3lame_static log)
