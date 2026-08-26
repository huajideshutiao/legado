cmake_minimum_required(VERSION 3.22.1)
project(legado_quickjs LANGUAGES C CXX)

# QuickJS-NG 源码目录 (单一数据源: 与 iOS/鸿蒙 cinterop 共用 shared/src/cinterop/quickjs-ng/)
get_filename_component(LEGADO_PROJECT_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../.." ABSOLUTE)
set(QUICKJS_NG_DIR "${LEGADO_PROJECT_ROOT}/shared/src/cinterop/quickjs-ng")

# ============ 通用瘦身开关 ============
# ThinLTO 跨 TU 内联+死码; gc-sections 回收无用 section。
# --icf=all (折叠相同函数) 仅 gold/lld 支持: Android NDK ld.lld 保留, 桌面 GNU BFD ld 不用 (见下方分支)。
# -fno-unwind-tables + -fno-asynchronous-unwind-tables: 已 -fno-exceptions, 不需要
#   .eh_frame / .eh_frame_hdr, 单 ABI 省 ~100 KB。也让 libunwind 静态段不再被拉入。
#
# KP1.1 跨平台注:
# - -flto=thin 是 Clang 特性, MSVC 不支持, 桌面 Windows MSVC 构建走 /O2 /GL
# - MinGW GCC 的 LTO 在 CI windows runner (C:\mingw64) 无法链接 (ld 无 lto plugin), 该分支不用 LTO
# - GNU ld 选项 (-Wl,--gc-sections / --exclude-libs=ALL) 仅 Linux 走, macOS/Windows 不用;
#   --icf=all 是 gold/lld 专属选项, 桌面 BFD ld 不支持
# - Android 端保持原行为 (NDK Clang + ThinLTO + GNU ld)
# - MSVC 不识别 GCC/Clang 选项 (-fvisibility=hidden / -ffunction-sections 等),
#   单独走 MSVC 选项 (/O2 /GL /Gy /Gw /EHsc /OPT:REF /OPT:ICF /LTCG)

# GCC/Clang 通用编译选项 (Android/Linux/macOS/MinGW)
set(LEGADO_QUICKJS_COMMON_FLAGS_GCC
        -fvisibility=hidden
        -ffunction-sections
        -fdata-sections
        -fno-unwind-tables
        -fno-asynchronous-unwind-tables
)
set(LEGADO_QUICKJS_CXX_FLAGS_GCC
        -fno-exceptions
        -fno-rtti
)
# --exclude-libs=ALL: 静态库符号不再重导出, 让 gc-sections + LTO 能真正剪掉 libc++_static
#                     里未被本 so 用到的部分 (libunwind / itanium_demangle 等)。
# Android 专属, 桌面端 GNU ld 也可用, 但 MSVC/Apple ld 不识别, 单独条件化处理。

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
# MSVC 不识别 GCC 选项, 单独走 /O2
if (MSVC)
    target_compile_options(quickjs_ng PRIVATE
            /O2
            /W3
    )
else ()
    target_compile_options(quickjs_ng PRIVATE
            ${LEGADO_QUICKJS_COMMON_FLAGS_GCC}
            -O2
            -Wall
            -Wno-unused-parameter
            -Wno-sign-compare
            -Wno-missing-field-initializers
            -Wno-implicit-fallthrough
    )
endif ()

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

# PREFIX "": 仅 Windows 桌面端去掉 lib 前缀, 让产出 legado_quickjs.dll
# - Windows MinGW: jvmMain/Platform.kt 用 System.load(absolutePath) 查找 legado_quickjs.dll (MinGW 默认 liblegado_quickjs.dll, 需 PREFIX "" 去前缀)
# - Windows MSVC: MSVC 默认无 lib 前缀, PREFIX "" 是 no-op
# - Android: System.loadLibrary("legado_quickjs") 自动查找 liblegado_quickjs.so (必须保留 lib 前缀, 否则 UnsatisfiedLinkError)
# - macOS/Linux: jvmMain 查找 liblegado_quickjs.dylib/.so (保留默认 lib 前缀)
# 注: 之前无条件 PREFIX "" 误伤 Android 端 (产出 legado_quickjs.so 无 lib 前缀, System.loadLibrary 找不到),
#     导致 java.lang.NoClassDefFoundError: com.script.quickjs.QuickJsNative 运行时崩溃
if (WIN32 AND NOT ANDROID)
    set_target_properties(legado_quickjs PROPERTIES PREFIX "")
endif ()

# JNI 头文件目录 (Android NDK 自带 jni.h, 桌面端从 JDK include 引入)
if (NOT ANDROID)
    # 优先用 JAVA_HOME, 其次探测常见 JDK 路径
    if (DEFINED ENV{JAVA_HOME})
        set(JDK_INCLUDE_DIR "$ENV{JAVA_HOME}/include")
    else ()
        # 探测常见 JDK 路径 (Windows/macOS/Linux)
        find_path(JDK_INCLUDE_DIR
                NAMES jni.h
                PATHS
                "C:/Program Files/Java/jdk-17/include"
                "C:/Program Files/Eclipse Adoptium/jdk-17/include"
                "/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home/include"
                "/usr/lib/jvm/java-17-openjdk-amd64/include"
                "/usr/lib/jvm/java-17-openjdk/include"
                NO_DEFAULT_PATH
        )
    endif ()

    if (JDK_INCLUDE_DIR AND EXISTS "${JDK_INCLUDE_DIR}/jni.h")
        message(STATUS "[legado_quickjs] Using JDK include: ${JDK_INCLUDE_DIR}")
        # 桌面平台子目录 (jni_md.h): win32/linux/darwin
        if (WIN32)
            set(JDK_OS_INCLUDE_DIR "${JDK_INCLUDE_DIR}/win32")
        elseif (APPLE)
            set(JDK_OS_INCLUDE_DIR "${JDK_INCLUDE_DIR}/darwin")
        else ()
            set(JDK_OS_INCLUDE_DIR "${JDK_INCLUDE_DIR}/linux")
        endif ()
    else ()
        message(FATAL_ERROR "[legado_quickjs] jni.h not found. Please set JAVA_HOME env var.")
    endif ()
endif ()

target_include_directories(legado_quickjs PRIVATE
        ${QUICKJS_NG_DIR}
        ${CMAKE_CURRENT_SOURCE_DIR}/jni
        ${JDK_INCLUDE_DIR}
        ${JDK_OS_INCLUDE_DIR}
)

if (MSVC)
    target_compile_options(legado_quickjs PRIVATE
            /O2
            /W3
            /EHsc
    )
else ()
    target_compile_options(legado_quickjs PRIVATE
            ${LEGADO_QUICKJS_COMMON_FLAGS_GCC}
            ${LEGADO_QUICKJS_CXX_FLAGS_GCC}
            -O2
            -Wall
            -Wno-unused-parameter
    )
endif ()

# ============ 平台专属链接配置 ============
if (ANDROID)
    # Android: NDK Clang + ThinLTO + GNU ld + 16KB 页面对齐
    # 保持原行为: -flto=thin + gc-sections/icf/exclude-libs + max-page-size=16384 + log 库
    target_compile_options(quickjs_ng PRIVATE -flto=thin)
    target_compile_options(legado_quickjs PRIVATE -flto=thin)

    # Android 15+ 要求 native 库 16 KB 页面对齐,否则在 16 KB 页面设备上可能无法安装
    # 链接器标志 -z max-page-size=16384 强制 ELF 对齐到 16 KB
    # 官方文档: https://developer.android.com/guide/practices/page-sizes
    target_link_options(legado_quickjs PRIVATE
            "-Wl,-z,max-page-size=16384"
            "-flto=thin"
            "-Wl,--gc-sections"
            "-Wl,--icf=all"
            "-Wl,--exclude-libs=ALL"
    )

    # Android: 链接 QuickJS-NG + Android log
    target_link_libraries(legado_quickjs
            PRIVATE
            quickjs_ng
            log
    )
elseif (MSVC)
    # 桌面 Windows MSVC: /GL (LTCG) + /Gy /Gw (function/data sections) + /OPT:REF/ICF
    target_compile_options(quickjs_ng PRIVATE /GL /Gy /Gw)
    target_compile_options(legado_quickjs PRIVATE /GL /Gy /Gw)
    target_link_options(legado_quickjs PRIVATE
            "/OPT:REF"
            "/OPT:ICF"
            "/LTCG"
    )
    # 桌面 Windows: 不链接 log, 不用 16KB 页面对齐
    target_link_libraries(legado_quickjs
            PRIVATE
            quickjs_ng
    )
else ()
    # 桌面 Linux/macOS/MinGW: GCC/Clang + GNU ld / Apple ld / lld
    # ThinLTO 仅 Clang 支持, 通过编译器检测决定是否启用
    if (CMAKE_C_COMPILER_ID STREQUAL "Clang" OR CMAKE_C_COMPILER_ID STREQUAL "AppleClang")
        target_compile_options(quickjs_ng PRIVATE -flto=thin)
        target_compile_options(legado_quickjs PRIVATE -flto=thin)
        set(LEGADO_QUICKJS_LTO_FLAG "-flto=thin")
    elseif (WIN32)
        # MinGW GCC: C:\mingw64 的 ar/ld 未装配 lto plugin (ar 报 "plugin needed to handle lto
        # object", 链接期大量 undefined reference to JS_*), 与 Linux binutils 不同; 关闭 LTO
        set(LEGADO_QUICKJS_LTO_FLAG "")
    else ()
        # Linux GCC: -flto (binutils 带 lto plugin, ubuntu CI 验证可链接)
        target_compile_options(quickjs_ng PRIVATE -flto)
        target_compile_options(legado_quickjs PRIVATE -flto)
        set(LEGADO_QUICKJS_LTO_FLAG "-flto")
    endif ()

    if (APPLE)
        # Apple ld: -dead_strip 等价 gc-sections, 不支持 --icf/--exclude-libs
        target_link_options(legado_quickjs PRIVATE
                "-Wl,-dead_strip"
                ${LEGADO_QUICKJS_LTO_FLAG}
        )
    elseif (WIN32)
        # MinGW: --gc-sections + -static。--icf=all 仅 gold/lld 支持 (C:\mingw64 的 BFD
        # ld 报 unrecognized option), 与 Linux 桌面分支一致去掉。
        # --exclude-libs=ALL 不支持 (lld 默认不重导出静态库符号, 无需此选项)
        # -static: 静态链接 UCRT + winpthread + libc++, 避免 DLL 依赖加载失败
        target_link_options(legado_quickjs PRIVATE
                "-Wl,--gc-sections"
                "-static"
                ${LEGADO_QUICKJS_LTO_FLAG}
        )
    else ()
        # Linux GNU ld: --gc-sections + --exclude-libs=ALL
        # (--icf=all 仅 gold/lld 支持, BFD ld 报 unrecognized option; ubuntu CI 的 /usr/bin/ld 即 BFD)
        target_link_options(legado_quickjs PRIVATE
                "-Wl,--gc-sections"
                "-Wl,--exclude-libs=ALL"
                ${LEGADO_QUICKJS_LTO_FLAG}
        )
    endif ()

    # 桌面 Linux/macOS/MinGW: 不链接 log
    # MinGW (LLVM-MinGW) 需链接 winpthread (pthread_mutex_* 等)
    if (WIN32 AND NOT MSVC)
        target_link_libraries(legado_quickjs
                PRIVATE
                quickjs_ng
                pthread
        )
    else ()
        target_link_libraries(legado_quickjs
                PRIVATE
                quickjs_ng
        )
    endif ()
endif ()
