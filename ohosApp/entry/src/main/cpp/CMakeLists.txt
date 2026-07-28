# CMakeLists.txt - 鸿蒙 entry 模块 napi 桥接层构建配置
#
# 用途: 把 liblegado_shared.so (Kotlin/Native linuxArm64 共享库) 中的 @CName 导出符号
#       包装为 napi module, 暴露给 ArkTS 调用。
#
# 调用链: ArkTS → liblegado_napi.so (本层) → dlsym → liblegado_shared.so (Kotlin/Native)
#
# 编译产物: liblegado_napi.so (鸿蒙 HAP 打包时会自动加入 libs/arm64-v8a/)

cmake_minimum_required(VERSION 3.5.0)
project(legado_napi)

# 鸿蒙 NDK 路径 (DevEco Studio 提供)
set(NATIVERENDER_ROOT_PATH ${OHOS_SDK_NATIVE})

# 头文件搜索路径
include_directories(
    ${NATIVERENDER_ROOT_PATH}/sysroot/usr/include
    ${NATIVERENDER_ROOT_PATH}/sysroot/usr/include/arm64-v8a
)

# 链接 liblegado_shared.so (Kotlin/Native 编译产物)
# 路径: ../../../../build/outputs/default/legado_shared.so
# (DevEco Studio 编译 entry 时把 .so 复制到此目录)
set(LEGADO_SHARED_LIB_PATH ${CMAKE_CURRENT_SOURCE_DIR}/../../../../libs/arm64-v8a/liblegado_shared.so)

# mbedTLS 3.6 LTS crypto (与 iOS 端共用 shared/src/cinterop/mbedtls/ 单一数据源)
# .c 编译进 liblegado_napi.so 并默认导出符号, liblegado_shared.so (Kotlin/Native cinterop 绑定)
# 中的未定义 mbedtls_* 引用在同一 dlopen 组内由本库解析
set(LEGADO_MBEDTLS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../shared/src/cinterop/mbedtls)
file(GLOB LEGADO_MBEDTLS_SRC ${LEGADO_MBEDTLS_DIR}/library/*.c)

# QuickJS-NG (与 iOS 端共用 shared/src/cinterop/quickjs-ng/ 单一数据源)
# 与 mbedtls 同理: cinterop 只编 quickjs.def 内的 qjs_* wrapper, 不编 quickjs-ng/ 的 C 源,
# 故 liblegado_shared.so 里的 JS_NewRuntime/JS_Eval 等符号是未定义引用, 须由本库提供,
# 否则 dlopen liblegado_shared.so 在设备上必然失败。
# 仅编核心 4 个文件, 不含 quickjs-libc.c (cutils 已合并进 quickjs.c, 是 quickjs-ng 的特性),
# 与 modules/quickjs/src/main/cpp/CMakeLists.txt 的 quickjs_ng 目标保持一致。
set(LEGADO_QUICKJS_NG_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../shared/src/cinterop/quickjs-ng)
set(LEGADO_QUICKJS_NG_SRC
    ${LEGADO_QUICKJS_NG_DIR}/quickjs.c
    ${LEGADO_QUICKJS_NG_DIR}/dtoa.c
    ${LEGADO_QUICKJS_NG_DIR}/libregexp.c
    ${LEGADO_QUICKJS_NG_DIR}/libunicode.c
)

# napi 桥接源文件 + mbedtls crypto 源 + quickjs-ng 源
add_library(legado_napi SHARED
    legado_napi.cpp
    ${LEGADO_MBEDTLS_SRC}
    ${LEGADO_QUICKJS_NG_SRC}
)

# mbedtls 头文件与裁剪配置注入 (legado_mbedtls_config.h 整体替换默认 mbedtls_config.h)
# quickjs-ng 头文件目录同时加入 (quickjs.h / quickjs-atom.h / cutils.h 等)
target_include_directories(legado_napi PRIVATE
    ${LEGADO_MBEDTLS_DIR}/include
    ${LEGADO_MBEDTLS_DIR}
    ${LEGADO_QUICKJS_NG_DIR}
)
target_compile_definitions(legado_napi PRIVATE
    MBEDTLS_CONFIG_FILE="legado_mbedtls_config.h"
    # quickjs-ng 上游要求 (见 quickjs-ng/CMakeLists.txt)
    _GNU_SOURCE
    QUICKJS_NG_BUILD
)

# quickjs-ng 要求 C11 + GNU 扩展 (计算 goto / __attribute__ 等)
# 本目标混编 .cpp 与 .c, C_STANDARD 仅作用于 C 源, 不影响 legado_napi.cpp
set_target_properties(legado_napi PROPERTIES
    C_STANDARD 11
    C_STANDARD_REQUIRED ON
    C_EXTENSIONS ON
)

# quickjs.c 告警噪声抑制 (与 modules/quickjs 一致, 仅作用于 C 源)
set_source_files_properties(${LEGADO_QUICKJS_NG_SRC} PROPERTIES
    COMPILE_OPTIONS "-Wno-unused-parameter;-Wno-sign-compare;-Wno-missing-field-initializers;-Wno-implicit-fallthrough"
)

# 链接 napi 与 legado_shared
target_link_libraries(legado_napi PUBLIC
    libace_napi.z.so
    libhilog_ndk.z.so
)

# 共享库由根 Gradle 任务 stageOhosNativeLibraries 统一准备。
# 缺产物时立即失败，禁止生成表面可安装、实际退化到 mock 的 HAP。
if(NOT EXISTS ${LEGADO_SHARED_LIB_PATH})
    message(FATAL_ERROR
        "liblegado_shared.so 未找到: ${LEGADO_SHARED_LIB_PATH}. "
        "请先在项目根目录运行: ./gradlew stageOhosNativeLibraries -PenableOhosTarget=true"
    )
endif()
target_link_libraries(legado_napi PUBLIC ${LEGADO_SHARED_LIB_PATH})
target_compile_definitions(legado_napi PRIVATE LEGADO_SHARED_AVAILABLE=1)

# 指定输出架构
set_target_properties(legado_napi PROPERTIES
    ARCHIVE_OUTPUT_DIRECTORY ${CMAKE_CURRENT_BINARY_DIR}
    LIBRARY_OUTPUT_DIRECTORY ${CMAKE_CURRENT_BINARY_DIR}
)
