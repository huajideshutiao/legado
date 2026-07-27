#include "../include/framework/antui_framework.h"
#include <dlfcn.h>
#include <node_api.h>
#include <stdlib.h>
#include <sys/eventfd.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <uv.h>
#include <functional>
#include <thread>
#include "../include/utils/common_ohos.h"
#include "../include/utils/common.h"
void PostTaskByUVLooper(void (*function)(void*), void* args) {
    napi_env env = AntUIFramework::GetTSEnv();
    uv_loop_s* uv_queue_loop;
    napi_get_uv_event_loop(env, &uv_queue_loop);
    uv_work_t* work = new uv_work_t;
    typedef AntUIFramework::Context<std::function<void()>> TaskContext;
    std::function<void()> invoke_runnable = [function, args]() { function(args); };
    auto context = new TaskContext(AntUIFramework::GetTSEnv(), invoke_runnable);
    work->data = context;
    uv_queue_work(
        uv_queue_loop, work, [](uv_work_t* work) {},
        [](uv_work_t* work, int status) {
            TaskContext* ctx = static_cast<TaskContext*>(work->data);
            ctx->task();
            delete ctx;
            delete work;
        });
}
struct AsyncContext {
    napi_async_work asyncWork = nullptr;
    std::function<void()> task;
    long delay_time;
};
static void AsyncWorkRunOnMain(napi_env env, napi_status status, void* data) {
    AsyncContext* asyncContext = reinterpret_cast<AsyncContext*>(data);
    asyncContext->task();
    napi_delete_async_work(AntUIFramework::GetTSEnv(), asyncContext->asyncWork);
    delete asyncContext;
}
static void DelayAtWorkerThread(napi_env env, void* data) {
    AsyncContext* callbackData = reinterpret_cast<AsyncContext*>(data);
    long delay_time = callbackData->delay_time;
    if (delay_time > 0) {
        std::this_thread::sleep_for(std::chrono::milliseconds(callbackData->delay_time));
    }
}
void PostAsyncWorkWithDelay(void (*function)(void*), void* args, const long time) {
    napi_env env = AntUIFramework::GetTSEnv();
    std::function<void()> invoke_runnable = [function, args]() { function(args); };
    auto asyncContext = new AsyncContext();
    asyncContext->task = invoke_runnable;
    asyncContext->delay_time = time;
    napi_value asyncName = nullptr;
    napi_create_string_utf8(env, "ohos_async_work_with_delay", NAPI_AUTO_LENGTH, &asyncName);
    napi_create_async_work(env, nullptr, asyncName, DelayAtWorkerThread, AsyncWorkRunOnMain, asyncContext, &asyncContext->asyncWork);
    napi_queue_async_work(env, asyncContext->asyncWork);
}
bool IsMainThread() {
    return AntUIFramework::NValueUtils::IsMainThread();
}
napi_env GetArkTsEnv() {
    return AntUIFramework::GetTSEnv();
}
