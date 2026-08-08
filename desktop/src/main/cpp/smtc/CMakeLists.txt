cmake_minimum_required(VERSION 3.18)
project(legado_smtc C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

add_library(legado_smtc SHARED smtc_bridge.c)

if (WIN32)
    # 去 lib 前缀: 输出 legado_smtc.dll; Windows 下 DLL 是 RUNTIME 产物, 指到 LIBRARY 输出目录
    set_target_properties(legado_smtc PROPERTIES
            PREFIX ""
            RUNTIME_OUTPUT_DIRECTORY "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}"
    )
    target_compile_definitions(legado_smtc PRIVATE UNICODE _UNICODE WIN32_LEAN_AND_MEAN INITGUID)
    target_link_libraries(legado_smtc PRIVATE ole32)
endif ()
