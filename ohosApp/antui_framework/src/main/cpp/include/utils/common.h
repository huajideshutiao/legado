#ifndef ANTUI_FRAMEWORK_COMMON_H
#define ANTUI_FRAMEWORK_COMMON_H
#include <hilog/log.h>
#include <string>
#include "common_ohos.h"
#include "napi/native_api.h"
#define FINFO(tag, format, ...) \
  OH_LOG_Print(LOG_APP, LOG_INFO, LOG_DOMAIN, tag, format, __VA_ARGS__);
#define FERROR(tag, format, ...) \
  OH_LOG_Print(LOG_APP, LOG_ERROR, LOG_DOMAIN, tag, format, __VA_ARGS__);
#endif
