cmake_minimum_required(VERSION 3.5.0)
project(AntUIFramework)
set(NATIVERENDER_ROOT_PATH ${CMAKE_CURRENT_SOURCE_DIR})
if(DEFINED PACKAGE_FIND_FILE)
    include(${PACKAGE_FIND_FILE})
endif()
include_directories(${NATIVERENDER_ROOT_PATH}
                    ${NATIVERENDER_ROOT_PATH}/include)
set(ANTUI_FRAMEWORK_SOURCES
    ${NATIVERENDER_ROOT_PATH}/include/utils/common.h
    ${NATIVERENDER_ROOT_PATH}/include/utils/common_ohos.h
    ${NATIVERENDER_ROOT_PATH}/utils/common_ohos.cpp
    ${NATIVERENDER_ROOT_PATH}/framework/antui_framework.cc
    ${NATIVERENDER_ROOT_PATH}/include/framework/antui_framework.h
    ${NATIVERENDER_ROOT_PATH}/xcomponent/xcomponent_common.cc
    ${NATIVERENDER_ROOT_PATH}/xcomponent/xcomponent_common.h
    ${NATIVERENDER_ROOT_PATH}/xcomponent/xcomponent_manager.cpp
    ${NATIVERENDER_ROOT_PATH}/xcomponent/xcomponent_manager.h
)
add_library(mykmp_framework SHARED napi_init.cpp ${ANTUI_FRAMEWORK_SOURCES})
target_link_libraries(mykmp_framework PUBLIC libace_napi.z.so libace_ndk.z.so libhilog_ndk.z.so libuv.so
    libnative_window.so libEGL.so libGLESv3.so libnative_vsync.so librawfile.z.so libffrt.z.so
    libohinputmethod.so libnative_image.so libohresmgr.so libnative_display_manager.so)
