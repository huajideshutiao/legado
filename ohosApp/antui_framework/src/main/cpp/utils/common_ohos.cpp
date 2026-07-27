#include "../include/utils/common_ohos.h"
#include <sys/syscall.h>
#include <unistd.h>
#include <cstring>
#include <string>
#include "napi/native_api.h"
namespace AntUIFramework {
static napi_env env_;
void InitTsEnv(napi_env env) {
    env_ = env;
}
napi_env GetTSEnv() {
    return env_;
}
napi_value NValueUtils::ConvertInt32ToNValue(const napi_env& env, const int32_t intValue) {
    napi_value result;
    napi_create_int32(env, intValue, &result);
    return result;
}
napi_value NValueUtils::ConvertInt64ToNValue(const napi_env& env, const int64_t intValue) {
    napi_value result;
    napi_create_int64(env, intValue, &result);
    return result;
}
napi_value NValueUtils::ConvertBoolToNValue(napi_env env, bool value) {
    napi_value temp;
    napi_value result;
    napi_create_int32(env, value ? 1 : 0, &temp);
    napi_coerce_to_bool(env, temp, &result);
    return result;
}
napi_value NValueUtils::ConvertDoubleToNValue(const napi_env env, double value) {
    napi_value result;
    napi_create_double(env, value, &result);
    return result;
}
napi_value NValueUtils::ConvertStringToNValue(const napi_env env, const std::string& value) {
    napi_value rtv = nullptr;
    napi_create_string_utf8(env, value.data(), value.size(), &rtv);
    return rtv;
}
std::string NValueUtils::GetStringFromValueUtf8(napi_env env, napi_value value) {
    size_t length = 0;
    if (napi_get_value_string_utf8(env, value, nullptr, 0, &length) != napi_ok) {
        return "";
    }
    char* buf = new char[length + 1];
    std::memset(buf, 0, length + 1);
    napi_get_value_string_utf8(env, value, buf, length + 1, &length);
    std::string std_string = std::string(buf, length);
    delete[] buf;
    return std_string;
}
bool NValueUtils::GetBoolFromNValue(napi_env env, napi_value value) {
    bool result{false};
    napi_get_value_bool(env, value, &result);
    return result;
}
int32_t NValueUtils::GetInt32FromNValue(napi_env env, napi_value value) {
    int32_t result{0};
    napi_get_value_int32(env, value, &result);
    return result;
}
int64_t NValueUtils::GetInt64FromNValue(napi_env env, napi_value value) {
    int64_t result{0};
    napi_get_value_int64(env, value, &result);
    return result;
}
double NValueUtils::GetDoubleFromNValue(napi_env env, napi_value value) {
    double result = 0.0;
    napi_get_value_double(env, value, &result);
    return result;
}
bool NValueUtils::IsMainThread() {
    pid_t pid = getpid();
    pid_t tid = syscall(SYS_gettid);
    return pid == tid;
}
}
