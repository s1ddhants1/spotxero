cmake_minimum_required(VERSION 3.22.1)

project("spotxero_native")

add_library(
        spotxero_native
        SHARED
        spotxero_native.cpp)

find_library(log-lib log)

target_link_libraries(
        spotxero_native
        ${log-lib})

target_link_options(spotxero_native PRIVATE "-Wl,-z,max-page-size=16384")
