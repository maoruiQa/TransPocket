# GGUF translation runtime. llama.cpp is fetched at configure time and is not committed.
# Pin v0.7.0: it loads Gemma 3 (TranslateGemma) and Qwen3.5 (Qwen3.5 0.8B, Index-Translate 2B).
#
# The CPU backend is built once per Android feature level (armv8.0, dotprod, i8mm, SVE, SME) and
# gguf_translator.cpp loads the best one the phone supports. A single armv8-a build never runs the
# dotprod/i8mm kernels or the Q4_0/IQ4_NL/Q4_K weight repacking, which is most of the speed on phones.
# BUILD_SHARED_LIBS is a normal variable here so the libraries added before this folder stay static.

include(FetchContent)

set(FETCHCONTENT_BASE_DIR "${CMAKE_SOURCE_DIR}/../../../../.cxx/llama-src" CACHE PATH "" FORCE)

set(BUILD_SHARED_LIBS ON)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
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

add_library(gguf_translator SHARED gguf_translator.cpp)
target_compile_features(gguf_translator PRIVATE cxx_std_17)
target_include_directories(gguf_translator PRIVATE
        ${llama_SOURCE_DIR}/include
        ${llama_SOURCE_DIR}/ggml/include)
target_link_libraries(gguf_translator PRIVATE llama ggml android log)
