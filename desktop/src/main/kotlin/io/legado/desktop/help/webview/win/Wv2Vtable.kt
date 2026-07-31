package io.legado.desktop.help.webview.win

/**
 * WebView2 COM 接口的 vtable 序号表 (0/1/2 恒为 IUnknown 的 QueryInterface/AddRef/Release)。
 *
 * 序号取自 WebView2 SDK 头文件的接口声明顺序 —— COM 单继承下派生接口的 vtable 是基接口
 * vtable 的追加, 故 `ICoreWebView2_2` 的序号可直接接在 `ICoreWebView2` 之后 (仍需先
 * QueryInterface 拿到正确指针, 见 [IID_ICORE_WEBVIEW2_2])。
 */
internal object Wv2 {

    // ICoreWebView2Environment
    const val ENV_CREATE_CONTROLLER = 3

    // ICoreWebView2Controller
    const val CTRL_PUT_IS_VISIBLE = 4
    const val CTRL_PUT_BOUNDS = 6
    const val CTRL_CLOSE = 24
    const val CTRL_GET_CORE_WEBVIEW2 = 25

    // ICoreWebView2
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

    // ICoreWebView2_2 (接在 ICoreWebView2 的 61 项之后)
    const val WV2_GET_COOKIE_MANAGER = 66

    // ICoreWebView2Settings / Settings2
    const val SETTINGS_PUT_IS_SCRIPT_ENABLED = 4
    const val SETTINGS_PUT_ARE_DEFAULT_SCRIPT_DIALOGS_ENABLED = 8
    const val SETTINGS_PUT_ARE_DEV_TOOLS_ENABLED = 12
    const val SETTINGS_PUT_IS_BUILT_IN_ERROR_PAGE_ENABLED = 20
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

    // ICoreWebView2WebResourceRequestedEventArgs / WebResourceRequest
    const val RES_ARGS_GET_REQUEST = 3
    const val REQUEST_GET_URI = 3

    /** COREWEBVIEW2_WEB_RESOURCE_CONTEXT_ALL */
    const val RESOURCE_CONTEXT_ALL = 0
}
