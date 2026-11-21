cmake_minimum_required(VERSION 3.18.1)
project(evgrab C)

add_executable(evgrab evgrab.c)
target_compile_options(evgrab PRIVATE -O2 -fPIE)
target_link_options(evgrab PRIVATE -pie)
set_target_properties(evgrab PROPERTIES
    PREFIX "lib"
    OUTPUT_NAME "evgrab"
    SUFFIX ".so"
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}"
)
