#ifndef ANTUI_FRAMEWORK_H
#define ANTUI_FRAMEWORK_H
#define EXPORT_API __attribute__((visibility("default")))
#include <pthread.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include "napi/native_api.h"
#if defined(__cplusplus) || defined(c_plusplus)
extern "C" {
#endif
napi_env EXPORT_API GetArkTsEnv();
void EXPORT_API RegisterArkTsFunction(const char* moduleName, const char* funcName);
void EXPORT_API PostTaskByUVLooper(void (*function)(void*), void* args);
void EXPORT_API PostAsyncWorkWithDelay(void (*function)(void*), void* args, const long time);
bool EXPORT_API IsMainThread();
#if defined(__cplusplus) || defined(c_plusplus)
}
#endif
#endif
