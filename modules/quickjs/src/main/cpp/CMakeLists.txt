cmake_minimum_required(VERSION 3.22.1)
project(legado_quickjs LANGUAGES C CXX)

# QuickJS-NG 源码目录
set(QUICKJS_NG_DIR ${CMAKE_CURRENT_SOURCE_DIR}/quickjs-ng)

# ============ 通用瘦身开关 ============
# ThinLTO 跨 TU 内联+死码; gc-sections/icf 回收 section 与折叠相同函数。
# -fno-unwind-tables + -fno-asynchronous-unwind-tables: 已 -fno-exceptions, 不需要
#   .eh_frame / .eh_frame_hdr, 单 ABI 省 ~100 KB。也让 libunwind 静态段不再被拉入。
set(LEGADO_QUICKJS_COMMON_FLAGS
        -fvisibility=hidden
        -ffunction-sections
        -fdata-sections
        -fno-unwind-tables
        -fno-asynchronous-unwind-tables
        -flto=thin
)
set(LEGADO_QUICKJS_CXX_FLAGS
        -fno-exceptions
        -fno-rtti
)
# --exclude-libs=ALL: 静态库符号不再重导出, 让 gc-sections + LTO 能真正剪掉 libc++_static
#                     里未被本 so 用到的部分 (libunwind / itanium_demangle 等)。
set(LEGADO_QUICKJS_LINK_FLAGS
        "-flto=thin"
        "-Wl,--gc-sections"
        "-Wl,--icf=all"
        "-Wl,--exclude-libs=ALL"
)

# ============ QuickJS-NG 静态库 ============
# 仅编译核心 4 个源文件,不含 quickjs-libc.c (QJS_BUILD_LIBC=OFF)
# cutils 已合并到 quickjs.c (quickjs-ng 特性)
add_library(quickjs_ng STATIC
        ${QUICKJS_NG_DIR}/quickjs.c
        ${QUICKJS_NG_DIR}/dtoa.c
        ${QUICKJS_NG_DIR}/libregexp.c
        ${QUICKJS_NG_DIR}/libunicode.c
)

target_include_directories(quickjs_ng PUBLIC ${QUICKJS_NG_DIR})

# 编译定义 (参考 quickjs-ng/CMakeLists.txt)
target_compile_definitions(quickjs_ng PRIVATE
        _GNU_SOURCE
        QUICKJS_NG_BUILD
)

# C11 标准,与 quickjs-ng 上游一致
set_target_properties(quickjs_ng PROPERTIES
        C_STANDARD 11
        C_STANDARD_REQUIRED ON
        C_EXTENSIONS ON
)

# 隐藏符号,减少 .so 体积
target_compile_options(quickjs_ng PRIVATE
        ${LEGADO_QUICKJS_COMMON_FLAGS}
        -O2
        -Wall
        -Wno-unused-parameter
        -Wno-sign-compare
        -Wno-missing-field-initializers
        -Wno-implicit-fallthrough
)

# ============ JNI 桥接层共享库 ============
add_library(legado_quickjs SHARED
        jni/jni_bridge.cpp
        jni/jni_handle.cpp
        jni/jni_object_class.cpp
        jni/jni_value_convert.cpp
        jni/jni_callbacks.cpp
)

# C++17 标准
set_target_properties(legado_quickjs PROPERTIES
        CXX_STANDARD 17
        CXX_STANDARD_REQUIRED ON
)

target_include_directories(legado_quickjs PRIVATE
        ${QUICKJS_NG_DIR}
        ${CMAKE_CURRENT_SOURCE_DIR}/jni
)

target_compile_options(legado_quickjs PRIVATE
        ${LEGADO_QUICKJS_COMMON_FLAGS}
        ${LEGADO_QUICKJS_CXX_FLAGS}
        -O2
        -Wall
        -Wno-unused-parameter
)

# Android 15+ 要求 native 库 16 KB 页面对齐,否则在 16 KB 页面设备上可能无法安装
# 链接器标志 -z max-page-size=16384 强制 ELF 对齐到 16 KB
# 官方文档: https://developer.android.com/guide/practices/page-sizes
target_link_options(legado_quickjs PRIVATE
        "-Wl,-z,max-page-size=16384"
        ${LEGADO_QUICKJS_LINK_FLAGS}
)

# 链接 QuickJS-NG + Android log
target_link_libraries(legado_quickjs
        PRIVATE
        quickjs_ng
        log
)
