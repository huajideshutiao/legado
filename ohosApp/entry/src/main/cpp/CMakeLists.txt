# HarmonyOS entry NAPI bridge for Legado + CPF-KMP-CMP fusion renderer.
cmake_minimum_required(VERSION 3.5.0)
project(legado_napi)

set(NATIVERENDER_ROOT_PATH ${OHOS_SDK_NATIVE})
set(MODULE_ROOT_PATH ${CMAKE_CURRENT_SOURCE_DIR}/../../..)

if (DEFINED PACKAGE_FIND_FILE)
    include(${PACKAGE_FIND_FILE})
endif ()

if (CMAKE_OHOS_ARCH_ABI STREQUAL "x86_64" OR OHOS_ARCH STREQUAL "x86_64")
    set(LEGADO_ABI_DIR "x86_64")
else ()
    set(LEGADO_ABI_DIR "arm64-v8a")
endif ()

include_directories(
    ${NATIVERENDER_ROOT_PATH}/sysroot/usr/include
        ${NATIVERENDER_ROOT_PATH}/sysroot/usr/include/${LEGADO_ABI_DIR}
        ${CMAKE_CURRENT_SOURCE_DIR}/include/${LEGADO_ABI_DIR}
)

set(COMPOSE_MODULE_PATH ${MODULE_ROOT_PATH}/oh_modules/@cpf-kmp-cmp/compose)
list(APPEND CMAKE_PREFIX_PATH ${COMPOSE_MODULE_PATH})
find_package(compose REQUIRED CONFIG)

set(LEGADO_SHARED_LIB_PATH ${CMAKE_CURRENT_SOURCE_DIR}/../../../libs/${LEGADO_ABI_DIR}/liblegado_shared.so)

# mbedTLS 3.6 LTS 与 QuickJS-NG 仍由 entry 原生层提供，供 K/N cinterop 未定义符号解析。
set(LEGADO_MBEDTLS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../shared/src/cinterop/mbedtls)
file(GLOB LEGADO_MBEDTLS_SRC ${LEGADO_MBEDTLS_DIR}/library/*.c)
set(LEGADO_QUICKJS_NG_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../shared/src/cinterop/quickjs-ng)
set(LEGADO_QUICKJS_NG_SRC
    ${LEGADO_QUICKJS_NG_DIR}/quickjs.c
    ${LEGADO_QUICKJS_NG_DIR}/dtoa.c
    ${LEGADO_QUICKJS_NG_DIR}/libregexp.c
    ${LEGADO_QUICKJS_NG_DIR}/libunicode.c
)

add_library(legado_napi SHARED
    legado_napi.cpp
    ${LEGADO_MBEDTLS_SRC}
    ${LEGADO_QUICKJS_NG_SRC}
)

target_include_directories(legado_napi PRIVATE
    ${LEGADO_MBEDTLS_DIR}/include
    ${LEGADO_MBEDTLS_DIR}
    ${LEGADO_QUICKJS_NG_DIR}
)
target_compile_definitions(legado_napi PRIVATE
    MBEDTLS_CONFIG_FILE="legado_mbedtls_config.h"
    _GNU_SOURCE
    QUICKJS_NG_BUILD
)
set_target_properties(legado_napi PROPERTIES
    C_STANDARD 11
    C_STANDARD_REQUIRED ON
    C_EXTENSIONS ON
)
set_source_files_properties(${LEGADO_QUICKJS_NG_SRC} PROPERTIES
    COMPILE_OPTIONS "-Wno-unused-parameter;-Wno-sign-compare;-Wno-missing-field-initializers;-Wno-implicit-fallthrough"
)

if(NOT EXISTS ${LEGADO_SHARED_LIB_PATH})
    message(FATAL_ERROR
            "liblegado_shared.so not found: ${LEGADO_SHARED_LIB_PATH}. "
            "Run the root stageOhosNativeLibraries task with enableOhosTarget=true."
    )
endif()

target_link_libraries(legado_napi PUBLIC
        libace_napi.z.so
        libhilog_ndk.z.so
        ${LEGADO_SHARED_LIB_PATH}
        # 目标名沿用 CPF 包的兼容命名；融合渲染时它提供 NAPI/RenderNode 桥，实际 backend id=1，
        # 不创建 XComponent/EGL 自渲染面。
        compose::skikobridge
)
target_compile_definitions(legado_napi PRIVATE LEGADO_SHARED_AVAILABLE=1)

set_target_properties(legado_napi PROPERTIES
    ARCHIVE_OUTPUT_DIRECTORY ${CMAKE_CURRENT_BINARY_DIR}
    LIBRARY_OUTPUT_DIRECTORY ${CMAKE_CURRENT_BINARY_DIR}
)
