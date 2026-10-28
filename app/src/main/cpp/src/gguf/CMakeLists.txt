# GGUF translation runtime. llama.cpp is fetched at configure time and is not committed.
# Pin v0.7.0: it loads Gemma 3 (TranslateGemma) and Qwen3.5 (Qwen3.5 0.8B, Index-Translate 2B).
#
# On arm64 and x86_64 the CPU backend is built once per feature level (arm64: armv8.0, dotprod,
# i8mm, SVE, SME; x86_64: SSE4.2 to AVX-512) and gguf_translator.cpp loads the one with the best
# score. A single baseline build never runs the dotprod/i8mm kernels or the Q4_0/IQ4_NL/Q4_K weight
# repacking, which is most of the speed on phones. 32-bit ABIs have no variants and link one CPU backend.
# BUILD_SHARED_LIBS is a normal variable here so the libraries added before this folder stay static.

include(FetchContent)

set(FETCHCONTENT_BASE_DIR "${CMAKE_SOURCE_DIR}/../../../../.cxx/llama-src" CACHE PATH "" FORCE)

set(BUILD_SHARED_LIBS ON)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
if(ANDROID_ABI STREQUAL "arm64-v8a" OR ANDROID_ABI STREQUAL "x86_64")
    set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
    set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
else()
    set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
    set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
endif()
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(GGML_BLAS OFF CACHE BOOL "" FORCE)
set(GGML_RPC OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_HTML OFF CACHE BOOL "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
# The CPU variants are MODULE libraries written to CMAKE_RUNTIME_OUTPUT_DIRECTORY. Put them next to
# the other JNI libraries so the APK packages them.
set(CMAKE_RUNTIME_OUTPUT_DIRECTORY ${CMAKE_LIBRARY_OUTPUT_DIRECTORY})

FetchContent_Declare(
        llama
        URL https://github.com/ggml-org/llama.cpp/archive/refs/tags/v0.7.0.tar.gz
)
FetchContent_MakeAvailable(llama)

# ggml names the arm64 variants libggml-cpu-android_armv8.0_1.so. Keep one dot in the file name;
# gguf_translator.cpp uses the same names.
foreach(variant android_armv8.0_1 android_armv8.2_1 android_armv8.2_2 android_armv8.6_1
        android_armv9.0_1 android_armv9.2_1 android_armv9.2_2)
    if(TARGET ggml-cpu-${variant})
        string(REPLACE "." "_" plain ${variant})
        set_target_properties(ggml-cpu-${variant} PROPERTIES OUTPUT_NAME ggml-cpu-${plain})
    endif()
endforeach()

add_library(gguf_translator SHARED gguf_translator.cpp)
target_compile_features(gguf_translator PRIVATE cxx_std_17)
target_include_directories(gguf_translator PRIVATE
        ${llama_SOURCE_DIR}/include
        ${llama_SOURCE_DIR}/ggml/include)
target_link_libraries(gguf_translator PRIVATE llama ggml android log dl)
