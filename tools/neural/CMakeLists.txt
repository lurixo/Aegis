# SPDX-License-Identifier: GPL-3.0-only

cmake_minimum_required(VERSION 3.22)
project(aegis_neural_tools CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(${CMAKE_CURRENT_LIST_DIR}/../../app/src/main/cpp/llama.cmake)

add_executable(aegis_neural_score
    score_cli.cpp
    ${CMAKE_CURRENT_LIST_DIR}/../../app/src/main/cpp/neural_scorer.cpp)
target_include_directories(aegis_neural_score PRIVATE ${CMAKE_CURRENT_LIST_DIR}/../../app/src/main/cpp)
target_link_libraries(aegis_neural_score PRIVATE llama)
