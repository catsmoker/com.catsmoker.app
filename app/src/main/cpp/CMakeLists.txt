cmake_minimum_required(VERSION 3.4.1)

# Set the library name
add_library(
        shizuku-native

        SHARED

        app.cpp
)

# Include headers
include_directories(${CMAKE_SYSTEM_INCLUDE_PATH})

# Link libraries
find_library(
        log-lib
        log
)

target_link_libraries(
        shizuku-native
        ${log-lib}
)
