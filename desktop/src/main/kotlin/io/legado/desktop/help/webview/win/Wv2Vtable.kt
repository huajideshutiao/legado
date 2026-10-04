package io.legado.desktop.help.webview.win

/**
 * WebView2 COM 接口的 vtable 序号表 (0/1/2 恒为 IUnknown 的 QueryInterface/AddRef/Release)。
 *
 * 序号取自 WebView2 SDK 头文件的接口声明顺序 —— COM 单继承下派生接口的 vtable 是基接口
 * vtable 的追加, 故 `ICoreWebView2_2` 的序号可直接接在 `ICoreWebView2` 之后 (仍需先
 * QueryInterface 拿到正确指针, 见 [IID_ICORE_WEBVIEW2_2])。
 *
 * 本轮新增序号 (请求头/响应替换/证书放行/自动播放) 的查证来源:
 * - `WebView2.idl` (Microsoft.Web.WebView2 NuGet 包内, 官方 MIDL 源):
 *   https://raw.githubusercontent.com/beyluta/WinWidgets/8e70f673c179338d983e7c9e1d471ed86a105e48/lib/WebView2/WebView2.idl
 *   各接口方法按声明顺序数槽位, 派生接口接在基接口之后;
 * - 与官方 WebView2.h 生成的 Go 绑定逐槽交叉验证 (unix-world/go-webview2-wails
 *   `pkg/webview2/ICoreWebView2*.go` 的 `*Vtbl` 结构体字段顺序):
 *   https://github.com/unix-world/go-webview2-wails/tree/599accbd4b53/pkg/webview2
 * - 与本文件既有**实测**常量一致 (`SETTINGS2_PUT_USER_AGENT=22`,
 *   `WV_ADD_WEB_RESOURCE_REQUESTED=55`, `WV_ADD_WEB_RESOURCE_REQUESTED_FILTER=57`,
 *   `WV2_GET_COOKIE_MANAGER=66`) —— IDL 推导值与实测值逐项吻合, 互为佐证。
 */
internal object Wv2 {

    // ICoreWebView2Environment
    const val ENV_CREATE_CONTROLLER = 3

    /** ICoreWebView2Environment::CreateWebResourceResponse (响应替换构造空响应用)。 */
    const val ENV_CREATE_WEB_RESOURCE_RESPONSE = 4

    // ICoreWebView2Environment2 (接在 Environment 的 5 项之后)
    /** ICoreWebView2Environment2::CreateWebResourceRequest (导航请求头全量透传用)。 */
    const val ENV2_CREATE_WEB_RESOURCE_REQUEST = 8

    /** ICoreWebView2EnvironmentOptions (环境级浏览器参数, 见 [Wv2EnvironmentOptions])。 */
    const val ENV_OPTIONS_GET_ADDITIONAL_ARGS = 3
    const val ENV_OPTIONS_PUT_ADDITIONAL_ARGS = 4
    const val ENV_OPTIONS_GET_LANGUAGE = 5
    const val ENV_OPTIONS_PUT_LANGUAGE = 6
    const val ENV_OPTIONS_GET_TARGET_VERSION = 7
    const val ENV_OPTIONS_PUT_TARGET_VERSION = 8
    const val ENV_OPTIONS_GET_SSO = 9
    const val ENV_OPTIONS_PUT_SSO = 10

    // ICoreWebView2Controller
    const val CTRL_PUT_IS_VISIBLE = 4
    const val CTRL_PUT_BOUNDS = 6
    const val CTRL_CLOSE = 24
    const val CTRL_GET_CORE_WEBVIEW2 = 25

    // ICoreWebView2 (序号经 runtime 实测验证: 55=add_WebResourceRequested 回调实测触发,
    // 57=AddWebResourceRequestedFilter 实测返回 S_OK; 50 实测返回 ERROR_NOT_FOUND 不是 add)
    const val WV_GET_SETTINGS = 3
    const val WV_GET_SOURCE = 4
    const val WV_NAVIGATE = 5
    const val WV_NAVIGATE_TO_STRING = 6
    const val WV_ADD_NAVIGATION_STARTING = 7
    const val WV_ADD_NAVIGATION_COMPLETED = 15
    const val WV_EXECUTE_SCRIPT = 29
    const val WV_RELOAD = 31
    const val WV_ADD_WEB_RESOURCE_REQUESTED = 55
    const val WV_ADD_WEB_RESOURCE_REQUESTED_FILTER = 57

    // ICoreWebView2_2 (接在 ICoreWebView2 的 58 项之后, 首槽 61)
    const val WV2_GET_COOKIE_MANAGER = 66

    /** ICoreWebView2_2::NavigateWithWebResourceRequest (导航时携带全量 headerMap)。 */
    const val WV2_NAVIGATE_WITH_WEB_RESOURCE_REQUEST = 63

    // ICoreWebView2 (权限请求事件, 用于放行 autoplay)
    /** ICoreWebView2::add_PermissionRequested (IDL 第 21 项, 首槽 3 → 23)。 */
    const val WV_ADD_PERMISSION_REQUESTED = 23

    // ICoreWebView2PermissionRequestedEventArgs
    const val PERM_ARGS_GET_PERMISSION_KIND = 4
    const val PERM_ARGS_PUT_STATE = 7

    /** COREWEBVIEW2_PERMISSION_KIND_AUTOPLAY (枚举第 10 项, 值 9)。 */
    const val PERMISSION_KIND_AUTOPLAY = 9

    /** COREWEBVIEW2_PERMISSION_STATE_ALLOW (枚举第 2 项, 值 1)。 */
    const val PERMISSION_STATE_ALLOW = 1

    // ICoreWebView2_14 (接在 ICoreWebView2_13 的 103 项之后, 首槽 106)
    /** ICoreWebView2_14::add_ServerCertificateErrorDetected (证书错误放行, 等价 proceed)。 */
    const val WV14_ADD_SERVER_CERTIFICATE_ERROR_DETECTED = 106

    // ICoreWebView2ServerCertificateErrorDetectedEventArgs
    /** put_Action: COREWEBVIEW2_SERVER_CERTIFICATE_ERROR_ACTION_ALWAYS_ALLOW = 0。 */
    const val CERT_ARGS_PUT_ACTION = 7
    const val CERT_ACTION_ALWAYS_ALLOW = 0

    // ICoreWebView2Settings / Settings2
    // 序号 2026-08 实测修正: 8 实际是 put_AreDefaultContextMenusEnabled(右键菜单),
    // 12 实际是 put_AreHostObjectsAllowed; 正确的 put 均为 get+1 (实测: 槽位 6 改 get(5)
    // AreDefaultScriptDialogsEnabled, 槽位 10 改 get(9) AreDevToolsEnabled)
    const val SETTINGS_PUT_IS_SCRIPT_ENABLED = 4 // get 3
    const val SETTINGS_PUT_ARE_DEFAULT_SCRIPT_DIALOGS_ENABLED = 6 // get 5
    const val SETTINGS_PUT_ARE_DEV_TOOLS_ENABLED = 10 // get 9
    const val SETTINGS_PUT_IS_BUILT_IN_ERROR_PAGE_ENABLED = 20 // get 19

    // Settings2 实测: 21=get_UserAgent, 22=put_UserAgent
    const val SETTINGS2_PUT_USER_AGENT = 22

    // ICoreWebView2CookieManager
    const val COOKIE_MGR_CREATE_COOKIE = 3
    const val COOKIE_MGR_GET_COOKIES = 5
    const val COOKIE_MGR_ADD_OR_UPDATE_COOKIE = 6

    // ICoreWebView2CookieList
    const val COOKIE_LIST_GET_COUNT = 3
    const val COOKIE_LIST_GET_ITEM = 4

    // ICoreWebView2Cookie
    const val COOKIE_GET_NAME = 3
    const val COOKIE_GET_VALUE = 4

    // ICoreWebView2NavigationStartingEventArgs
    const val NAV_START_GET_URI = 3
    const val NAV_START_GET_IS_REDIRECTED = 5
    const val NAV_START_PUT_CANCEL = 8

    // ICoreWebView2NavigationCompletedEventArgs
    const val NAV_COMPLETED_GET_IS_SUCCESS = 3

    // ICoreWebView2WebResourceRequestedEventArgs / WebResourceRequest
    const val RES_ARGS_GET_REQUEST = 3

    /** ICoreWebView2WebResourceRequestedEventArgs::put_Response (命中即返回空响应吞掉请求)。 */
    const val RES_ARGS_PUT_RESPONSE = 5
    const val REQUEST_GET_URI = 3

    /** ICoreWebView2WebResourceRequest::get_Headers (第 7 项, 首槽 3 → 9)。 */
    const val REQUEST_GET_HEADERS = 9

    // ICoreWebView2HttpRequestHeaders (全 IUnknown 派生, 首槽 3)
    /** GetHeader(name, LPWSTR*) —— 取单个请求头。 */
    const val HEADERS_GET_HEADER = 3
    const val HEADERS_GET_HEADERS = 4
    const val HEADERS_CONTAINS = 5
    /** SetHeader(name, value) —— 改写请求头 (WebResourceRequested 内可改)。 */
    const val HEADERS_SET_HEADER = 6
    const val HEADERS_REMOVE_HEADER = 7
    /** GetIterator(out ICoreWebView2HttpHeadersCollectionIterator**) —— 遍历全部请求头。 */
    const val HEADERS_GET_ITERATOR = 8

    // ICoreWebView2HttpHeadersCollectionIterator (全 IUnknown 派生, 首槽 3)
    /** GetCurrentHeader(LPWSTR* name, LPWSTR* value)。 */
    const val HEADERS_ITER_GET_CURRENT = 3
    /** get_HasCurrentHeader(BOOL*)。 */
    const val HEADERS_ITER_HAS_CURRENT = 4
    /** MoveNext(BOOL* hasNext)。 */
    const val HEADERS_ITER_MOVE_NEXT = 5

    /** COREWEBVIEW2_WEB_RESOURCE_CONTEXT_ALL */
    const val RESOURCE_CONTEXT_ALL = 0
}
