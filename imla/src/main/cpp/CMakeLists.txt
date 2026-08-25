cmake_minimum_required(VERSION 3.22.1)
project(ahb_id)

add_library(ahb_id SHARED ahb_id.cpp)
target_link_libraries(ahb_id android)
target_link_options(ahb_id PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)
