#ifndef ANTUI_FRAMEWORK_COMMON_OHOS_H
#define ANTUI_FRAMEWORK_COMMON_OHOS_H
#include <hilog/log.h>
#include <napi/native_api.h>
#include <resourcemanager/ohresmgr.h>
#include <string.h>
#include <uv.h>
#include <future>
#include <string>
static const char* KMPSONAME = "libAntUI.so";
namespace AntUIFramework {
template <typename T>
class Context {
 public:
  Context(napi_env env, T task) : env(env), task(std::move(task)) {}
  napi_env env;
  T task;
};
void InitTsEnv(napi_env env);
napi_env GetTSEnv();
class NValueUtils {
 public:
  static napi_value ConvertInt32ToNValue(const napi_env& env, const int32_t intValue);
  static napi_value ConvertInt64ToNValue(const napi_env& env, const int64_t intValue);
  static napi_value ConvertBoolToNValue(const napi_env env, bool value);
  static napi_value ConvertDoubleToNValue(const napi_env env, double value);
  static napi_value ConvertStringToNValue(const napi_env env, const std::string& value);
  static std::string GetStringFromValueUtf8(napi_env env, napi_value value);
  static int32_t GetInt32FromNValue(napi_env env, napi_value value);
  static int64_t GetInt64FromNValue(napi_env env, napi_value value);
  static bool GetBoolFromNValue(napi_env env, napi_value value);
  static double GetDoubleFromNValue(napi_env env, napi_value value);
  static bool IsMainThread();
};
}
#endif
