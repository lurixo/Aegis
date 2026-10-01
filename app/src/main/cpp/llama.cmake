# SPDX-License-Identifier: GPL-3.0-only

include(FetchContent)

add_compile_options(
    "-ffile-prefix-map=${CMAKE_BINARY_DIR}=."
    "-ffile-prefix-map=${CMAKE_SOURCE_DIR}=.")

set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)

if(ANDROID)
    set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
    if(CMAKE_SYSTEM_PROCESSOR MATCHES "aarch64|arm64")
        set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
    elseif(CMAKE_SYSTEM_PROCESSOR MATCHES "x86_64")
        set(GGML_AVX2 OFF CACHE BOOL "" FORCE)
        set(GGML_FMA OFF CACHE BOOL "" FORCE)
        set(GGML_BMI2 OFF CACHE BOOL "" FORCE)
    endif()
endif()

FetchContent_Declare(llama_cpp
    URL https://github.com/ggml-org/llama.cpp/archive/refs/tags/v0.5.0.tar.gz
    URL_HASH SHA256=fef9ed754f4e031fb5c663c29260feda4ebc241abb68d64a81c0f1df5f1748e2
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE)
FetchContent_MakeAvailable(llama_cpp)
