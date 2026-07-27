#include "napi/native_api.h"
#include <dlfcn.h>
#include <rawfile/raw_file.h>
#include <rawfile/raw_file_manager.h>
#include <string>
#include "./include/utils/common_ohos.h"
#include "./xcomponent/xcomponent_common.h"
static napi_value NativeInitFramework(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1] = {nullptr};
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    NativeResourceManager* native_res_mgr = OH_ResourceManager_InitNativeResourceManager(env, args[0]);
    typedef void (*RegisterNativeResourceManager)(void*);
    void* kmp_handler = dlopen(KMPSONAME, RTLD_NOW);
    if (kmp_handler != nullptr) {
        auto init_func = (RegisterNativeResourceManager)dlsym(kmp_handler, "KMPFramework_initNativeResourceManager");
        if (init_func) {
            init_func(reinterpret_cast<void*>(native_res_mgr));
        }
    }
    return nullptr;
}
static napi_value CreateComposeView(napi_env env, napi_callback_info info) {
    size_t argc = 2;
    napi_value args[2] = {nullptr};
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    auto create_function_name = AntUIFramework::NValueUtils::GetStringFromValueUtf8(env, args[0]);
    auto component_id = AntUIFramework::NValueUtils::GetStringFromValueUtf8(env, args[1]);
    typedef void (*CreateComposeViewFunc)(const char*);
    void* kmp_handler = dlopen(KMPSONAME, RTLD_NOW);
    if (kmp_handler != nullptr) {
        auto create_func = (CreateComposeViewFunc)dlsym(kmp_handler, create_function_name.c_str());
        if (create_func) {
            create_func(component_id.c_str());
        }
    }
    return nullptr;
}
static napi_value CallKotlinFunction(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1] = {nullptr};
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    auto function_name = AntUIFramework::NValueUtils::GetStringFromValueUtf8(env, args[0]);
    typedef napi_value (*CallKotlinFunc)(napi_callback_info info);
    void* kmp_handler = dlopen(KMPSONAME, RTLD_NOW);
    auto trigger_func = (CallKotlinFunc)dlsym(kmp_handler, function_name.c_str());
    napi_value result = nullptr;
    if (trigger_func) {
        result = trigger_func(info);
    }
    return result;
}
static napi_value RegisterFunction(napi_env env, napi_callback_info info) {
    typedef void (*registerArkTsFunc)(napi_callback_info);
    void* kmp_handler = dlopen(KMPSONAME, RTLD_NOW);
    auto register_func = (registerArkTsFunc)dlsym(kmp_handler, "KmpFramework_registerArkTSFunction");
    if (register_func) {
        register_func(info);
    }
    return nullptr;
}
EXTERN_C_START
static napi_value Init(napi_env env, napi_value exports) {
    napi_property_descriptor desc[] = {
        {"nativeInitFramework", nullptr, NativeInitFramework, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"CallKotlinFunction", nullptr, CallKotlinFunction, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"RegisterFunction", nullptr, RegisterFunction, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"createNativeNodeContent", nullptr, CreateNativeNodeContent, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"nativeCreateComposeView", nullptr, CreateComposeView, nullptr, nullptr, nullptr, napi_default, nullptr},
    };
    napi_define_properties(env, exports, sizeof(desc) / sizeof(desc[0]), desc);
    AntUIFramework::InitTsEnv(env);
    typedef void (*InitKmpFramework)(void*, void*);
    void* kmp_handler = dlopen(KMPSONAME, RTLD_NOW);
    if (kmp_handler != nullptr) {
        auto kmp_init_func = (InitKmpFramework)dlsym(kmp_handler, "KMPFramework_initKmpFramework");
        if (kmp_init_func) {
            kmp_init_func(reinterpret_cast<void*>(env), reinterpret_cast<void*>(exports));
        }
    }
    ExportToNativeXComponent(env, exports);
    return exports;
}
EXTERN_C_END
static napi_module demoModule = {
    .nm_version = 1,
    .nm_flags = 0,
    .nm_filename = nullptr,
    .nm_register_func = Init,
    .nm_modname = "mykmp_framework",
    .nm_priv = ((void*)0),
    .reserved = {0},
};
extern "C" __attribute__((constructor)) void RegisterAntUIFrameworkModule(void) {
    napi_module_register(&demoModule);
}
