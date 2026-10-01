cmake_minimum_required(VERSION 3.22.1)

project(dshpty LANGUAGES C)

add_library(dshpty SHARED native_pty.c)

target_compile_features(dshpty PRIVATE c_std_11)
target_compile_options(dshpty PRIVATE -Wall -Wextra -Werror)
