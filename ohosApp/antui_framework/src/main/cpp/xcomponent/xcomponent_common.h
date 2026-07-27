#ifndef XCOMPONENT_COMMON_H
#define XCOMPONENT_COMMON_H
#include <napi/native_api.h>
napi_value CreateNativeNodeContent(napi_env env, napi_callback_info info);
void ExportToNativeXComponent(napi_env env, napi_value exports);
#endif
