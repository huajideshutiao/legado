#include "xcomponent_common.h"
#include <dlfcn.h>
#include <hilog/log.h>
#include "../include/utils/common_ohos.h"
#include "../include/utils/common.h"
#include <arkui/native_node_napi.h>
void ExportToNativeXComponent(napi_env env, napi_value exports) {
    FINFO("XCOMPONENT", "%{public}s", "Begin dlopen kmp so");
    typedef void (*RegisterXComponent)(void*, void*);
    void* kmp_handler = dlopen(KMPSONAME, RTLD_NOW);
    if (kmp_handler == nullptr) {
        FERROR("AntUIFramework", "dlopen fail: %{public}s", dlerror());
    } else {
        auto register_func = (RegisterXComponent)dlsym(kmp_handler, "KMPFramework_initKmpFramework");
        if (register_func) {
            register_func(env, exports);
        }
    }
}
napi_value CreateNativeNodeContent(napi_env env, napi_callback_info info) {
    FINFO("XCOMPONENT", "%{public}s", "Begin init node content");
    size_t argc = 4;
    napi_value args[4] = {nullptr};
    if (napi_ok != napi_get_cb_info(env, info, &argc, args, nullptr, nullptr)) {
        return nullptr;
    }
    auto component_id = AntUIFramework::NValueUtils::GetStringFromValueUtf8(env, args[0]);
    ArkUI_NodeContentHandle native_content_handle = nullptr;
    OH_ArkUI_GetNodeContentFromNapiValue(env, args[1], &native_content_handle);
    typedef void (*InitNativeNodeContent)(const char*, void*, void*, void*);
    void* kmp_handler = dlopen(KMPSONAME, RTLD_NOW);
    if (kmp_handler == nullptr) {
        return nullptr;
    } else {
        auto init_func = (InitNativeNodeContent)dlsym(kmp_handler, "KMPFramework_initNativeNodeContent");
        if (init_func) {
            init_func(component_id.c_str(), reinterpret_cast<void*>(native_content_handle), args[2], args[3]);
        }
    }
    return nullptr;
}
