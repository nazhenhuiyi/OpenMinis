cmake_minimum_required(VERSION 3.22.1)
project(minis_native CXX C)

add_library(pty_bridge SHARED pty_bridge.c)

# T283: NDK signal handler for native crashes (SIGSEGV/SIGABRT/...).
# Separate library so loadLibrary() can pull it in independently of
# pty_bridge — the crash handler must be installed before any other
# native lib has a chance to fault.
add_library(minis_crash_handler SHARED crash_handler.cpp)

# [T-android-text-segmenter] CppJieba JNI bridge for TextSegmenter's Chinese
# path. cppjieba is header-only (no static lib to build); this version vendors
# limonp inline, so a single include root (cppjieba/include) is all it needs.
# Headers are included as "cppjieba/<Name>.hpp", so we add the parent dir.
add_library(jieba_jni SHARED jieba_jni.cpp)
target_include_directories(jieba_jni PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/cppjieba/include
)

find_library(log-lib log)
target_link_libraries(pty_bridge ${log-lib})
target_link_libraries(minis_crash_handler ${log-lib})
target_link_libraries(jieba_jni ${log-lib})

# [T-today-murmur] llama.cpp for the on-device 碎碎念 tagger (SPEC §19,
# docs/design/today/MURMUR_MODEL.md). Pinned to the commit the phone was
# measured with. Fetched at configure time; pass
# -DFETCHCONTENT_SOURCE_DIR_LLAMA=<checkout> to build from a local tree.
# Baseline ARMv8.2 + dotprod + fp16 (every phone since ~2019): the Q4_0
# weights are repacked for dotprod; i8mm would be faster on new chips but
# would crash older ones without runtime CPU dispatch.
include(FetchContent)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
FetchContent_Declare(llama
    URL https://github.com/ggml-org/llama.cpp/archive/e36df604703dec1b6ce82f214698725475a57035.tar.gz
)
FetchContent_MakeAvailable(llama)

add_library(murmur_llm SHARED murmur_llm.cpp)
target_link_libraries(murmur_llm llama ${log-lib})
# Debug builds compile at -O0, which makes the model ~20× slower; the
# inference code is always optimised (the JNI bridge stays debuggable).
foreach(t ggml ggml-base ggml-cpu llama)
    if(TARGET ${t})
        target_compile_options(${t} PRIVATE -O3)
    endif()
endforeach()
