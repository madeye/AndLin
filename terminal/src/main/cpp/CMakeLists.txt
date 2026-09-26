cmake_minimum_required(VERSION 3.22.1)
project(anl_terminal C)

add_library(anl-terminal SHARED anl_terminal.c)
target_compile_options(anl-terminal PRIVATE -Wall -Wextra -Werror -O2)
# 16 KB page alignment so the library loads on 16 KB page-size devices.
target_link_options(anl-terminal PRIVATE "-Wl,-z,max-page-size=16384")
target_link_libraries(anl-terminal log)
