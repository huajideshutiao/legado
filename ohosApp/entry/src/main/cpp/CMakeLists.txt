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

# napi 桥接源文件
add_library(legado_napi SHARED
    legado_napi.cpp
)

# 链接 napi 与 legado_shared
target_link_libraries(legado_napi PUBLIC
    libace_napi.z.so
    libhilog_ndk.z.so
)

# 如果 liblegado_shared.so 存在则链接 (KP4 阶段可能尚未编译)
if(EXISTS ${LEGADO_SHARED_LIB_PATH})
    target_link_libraries(legado_napi PUBLIC ${LEGADO_SHARED_LIB_PATH})
    target_compile_definitions(legado_napi PRIVATE LEGADO_SHARED_AVAILABLE=1)
else()
    target_compile_definitions(legado_napi PRIVATE LEGADO_SHARED_AVAILABLE=0)
    message(WARNING "liblegado_shared.so 未找到, napi 桥接层将退化为 mock 实现")
endif()

# 指定输出架构
set_target_properties(legado_napi PROPERTIES
    ARCHIVE_OUTPUT_DIRECTORY ${CMAKE_CURRENT_BINARY_DIR}
    LIBRARY_OUTPUT_DIRECTORY ${CMAKE_CURRENT_BINARY_DIR}
)
