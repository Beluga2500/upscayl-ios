cmake_minimum_required(VERSION 3.18)
project(upscayl_jni)

set(CMAKE_CXX_STANDARD 17)

# ncnn précompilé (android-vulkan) téléchargé par le workflow dans ./ncnn
set(ncnn_DIR ${CMAKE_CURRENT_SOURCE_DIR}/ncnn/${ANDROID_ABI}/lib/cmake/ncnn)
find_package(ncnn REQUIRED)

add_library(upscayl SHARED upscayl_jni.cpp)
target_link_libraries(upscayl ncnn android jnigraphics log)
