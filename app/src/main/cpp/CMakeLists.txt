# SPDX-License-Identifier: GPL-3.0-only

cmake_minimum_required(VERSION 3.22)
project(aegis_neural CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(${CMAKE_CURRENT_LIST_DIR}/llama.cmake)

add_library(aegis_neural SHARED neural_jni.cpp neural_scorer.cpp)
target_link_libraries(aegis_neural PRIVATE llama)
target_link_options(aegis_neural PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,--gc-sections")
