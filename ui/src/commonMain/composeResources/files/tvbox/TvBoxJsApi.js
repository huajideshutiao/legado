/*
 * TVBox JS spider 宿主门面 (JS 侧)。
 *
 * 逐条对齐 FongMi/TV fongmi 分支 quickjs 模块注入给 spider JS 的全局面:
 *   quickjs/src/main/java/com/fongmi/quickjs/method/Global.java  (@JSMethod 全局函数)
 *   quickjs/src/main/java/com/fongmi/quickjs/method/Local.java    (local 对象)
 *   quickjs/src/main/assets/js/lib/http.js                        (http/req 包装)
 *
 * 差异 (本仓库 QuickJS 无 job 泵, 见 TvBoxJsModuleLoader.js 注释):
 * - req / _http / http 全部阻塞同步, 返回 {code, headers, content} 普通对象 (非 Promise);
 *   对 `await req(...)` 无影响 (去 await 后即终值), 但依赖 Promise/async 语义的
 *   (Promise.all / .then 链式 / setTimeout 延时回调) 无法支持。
 * - setTimeout 立即同步执行 (不延时), 保证不因无人泵 job 而永久挂起。
 */

/** 宿主 HTTP: 阻塞 OkHttp, 返回 {code, headers, content}。 */
function _http(url, options) {
    return JSON.parse(__M.jsonCall('req', url, JSON.stringify(options || {})));
}

/** http.js 同款包装: 同步返回, 兼容 `http(url, {async:false})` 调用面。 */
function http(url, options) {
    return _http(url, options);
}

/** http.js 同款: req 即 async:false 的 http。 */
function req(url, options) {
    var opts = options || {};
    opts.async = false;
    return _http(url, opts);
}

/** URI 解析 (FongMi Global.joinUrl → UriUtil.resolve)。 */
function joinUrl(parent, child) {
    return __M.jsonCall('joinUrl', parent, child);
}

/** 本地代理入口 (FongMi Global.getProxy/getPort/js2Proxy)。 */
function getProxy(isLocal) {
    return __M.jsonCall('getProxy', isLocal === true ? '1' : '0');
}

function getPort() {
    return parseInt(__M.jsonCall('getPort') || '0', 10);
}

function js2Proxy(dynamic, siteType, siteKey, url, headers) {
    return __M.jsonCall('js2Proxy',
        dynamic === true ? '1' : '0',
        siteType == null ? '3' : String(siteType),
        siteKey == null ? '' : String(siteKey),
        url == null ? '' : String(url),
        JSON.stringify(headers || {}));
}

/** 加解密 (FongMi Global.md5X/aesX/desX/rsaX → catvod Crypto)。 */
function md5X(text) {
    return __M.jsonCall('md5', text);
}

function aesX(mode, encrypt, input, inBase64, key, iv, outBase64) {
    return __M.jsonCall('aes', mode, encrypt ? '1' : '0', input,
        inBase64 ? '1' : '0', key, iv, outBase64 ? '1' : '0');
}

function desX(mode, encrypt, input, inBase64, key, iv, outBase64) {
    return __M.jsonCall('des', mode, encrypt ? '1' : '0', input,
        inBase64 ? '1' : '0', key, iv, outBase64 ? '1' : '0');
}

function rsaX(mode, pub, encrypt, input, inBase64, key, outBase64) {
    return __M.jsonCall('rsa', mode, pub ? '1' : '0', encrypt ? '1' : '0',
        input, inBase64 ? '1' : '0', key, outBase64 ? '1' : '0');
}

/** 简繁转换 (FongMi Global.s2t/t2s)。 */
function s2t(text) {
    return __M.jsonCall('s2t', text);
}

function t2s(text) {
    return __M.jsonCall('t2s', text);
}

/**
 * 定时器: 立即同步执行 (无 job 泵, 延时回调永远不会触发)。
 * 返回 id 供 clearTimeout 记账, 语义与 FongMi 一致 (只是不延时)。
 */
var __timers = {};

function setTimeout(func, delay) {
    var id = Object.keys(__timers).length + 1;
    __timers[id] = func;
    try {
        if (typeof func === 'function') func();
    } finally {
        delete __timers[id];
    }
    return id;
}

function setInterval(func, delay) {
    return setTimeout(func, delay);
}

function clearTimeout(id) {
    delete __timers[id];
    return null;
}

function clearInterval(id) {
    return clearTimeout(id);
}

/** 本地键值存储 (FongMi method/Local: get/set/delete)。 */
var local = {
    get: function (rule, key) {
        return __M.jsonCall('localGet', rule == null ? '' : String(rule), key == null ? '' : String(key));
    },
    set: function (rule, key, value) {
        return __M.jsonCall('localSet', rule == null ? '' : String(rule),
            key == null ? '' : String(key), value == null ? '' : String(value));
    },
    delete: function (rule, key) {
        return __M.jsonCall('localDelete', rule == null ? '' : String(rule), key == null ? '' : String(key));
    }
};

/** 宿主日志 (落 legado AppLog/Logcat)。 */
var console = {
    log: function () {
        __M.jsonCall('log', Array.prototype.slice.call(arguments).map(String).join(' '));
    },
    info: function () {
        __M.jsonCall('log', Array.prototype.slice.call(arguments).map(String).join(' '));
    },
    warn: function () {
        __M.jsonCall('log', Array.prototype.slice.call(arguments).map(String).join(' '));
    },
    debug: function () {
        __M.jsonCall('log', Array.prototype.slice.call(arguments).map(String).join(' '));
    },
    error: function () {
        __M.jsonCall('log', 'ERROR ' + Array.prototype.slice.call(arguments).map(String).join(' '));
    },
    print: function () {
        __M.jsonCall('log', Array.prototype.slice.call(arguments).map(String).join(' '));
    }
};

/** FongMi http.js 的 global/window/self 别名 (spider 常按浏览器习惯写)。 */
['global', 'window', 'self'].forEach(function (name) {
    var desc = Object.getOwnPropertyDescriptor(globalThis, name);
    if (desc && !desc.configurable) return;
    Object.defineProperty(globalThis, name, {
        enumerable: true,
        configurable: true,
        get: function () {
            return globalThis;
        },
        set: function () {
        }
    });
});
