package com.script.quickjs

/**
 * JS bootstrap 脚本, 在 QuickJs ctx 创建后立即 evaluate。注入:
 * 1. `Packages` + `java`/`javax`/`android`/`org`/`com`/`io`/`cn` 顶级别名 (Proxy 按需 __loadJavaClass)。
 * 2. `JavaImporter` / `importClass` / `importPackage` —— rhino LiveConnect 兼容 API。
 * 3. `JavaAdapter` —— 走 `__newJavaAdapter` 创建 java.lang.reflect.Proxy。
 * 4. `__wrapClass` —— Class 句柄的 JS 包装 (构造 + 静态成员)。
 * 5. `__bindingsStack__` + `__enterBindings` / `__exitBindings` / `__currentBindings` ——
 *    [evalInSubScope] 用的子 scope 栈, 必须保留在 JS 层 (with 语句需要 JS 对象)。
 *
 * 精简版 (方案B): 移除 __dangerousApi__ 全局变量和 __cleanupBindings/__bootstrapGlobals__。
 * - dangerousApi 从 native ctx opaque 读取 (native binding __getDangerousApi, JS 层不定义 stub,
 *   否则 top-level function 声明会覆盖同名 globalThis 属性, 让 native binding 失效)
 * - cleanup 在 Kotlin 层用 nativeSetPropertyHandle 恢复 bootstrap 初值 / nativeDeleteProperty 真删除
 *
 * 依赖的 native binding (由 QuickJsEngine.registerBindings 注册):
 * - `__loadJavaClass(fullName, dangerousApi): Long`
 * - `__classExists(fullName, dangerousApi): Boolean`
 * - `__isInterface(classHandle, dangerousApi): Boolean`
 * - `__newJavaInstance(classHandle, args, dangerousApi): Any?`
 * - `__callStaticMethod(classHandle, methodName, args, dangerousApi): Any?`
 * - `__getStaticField(classHandle, fieldName, dangerousApi): Any?`
 * - `__setStaticField(classHandle, fieldName, value, dangerousApi): Boolean`
 * - `__newJavaAdapter(classHandle, jsFnHandle, dangerousApi): Any?`
 * - `__registerJsFunctionNative(jsObjectExpr, dangerousApi): Long`
 * - `__wrapJavaHandle(handle): Any?`
 * - `__getDangerousApi(): Boolean` (native 层直接从 ctx opaque 读取, 不走 Kotlin BindingHandler)
 */
object JsBootstrap {

    val code: String = """
// ============ bindings 子 scope 栈 ============
// evalInSubScope 把 bindings 装入独立 JS 对象压栈, wrapJsForEval 的 with(__currentBindings())
// 让 user JS 以标识符 (java/cache/source) 命中. 空栈时穿透到 globalThis.
// 必须保留在 JS 层: with 语句需要 JS 对象作为目标。
var __bindingsStack__ = [];

function __currentBindings() {
    return __bindingsStack__[__bindingsStack__.length - 1] || {};
}

// 变长参数 (k1,v1,k2,v2,...), 组新对象压栈.
// 同时写入 globalThis, 让 jsLib 共享函数内 this.java/source/cache 等命中 bindings.
// 标准 JS: 裸函数调用 func() 在 sloppy mode 下 this = globalThis,
// jsLib 函数定义在 topScope, 被 user JS 调用时 this 不指向 bindings.
// rhino 的 scope-based this 让 this = childScope (含 bindings), 此处模拟该行为.
// exitBindings 自动恢复原值, 不污染 topScope.
function __enterBindings() {
    var obj = {};
    for (var i = 0; i < arguments.length; i += 2) {
        obj[arguments[i]] = arguments[i + 1];
    }
    // 同步写入 globalThis, 让 this.java 在 jsLib 函数内生效
    var prev = {};
    var existed = {};
    var keys = Object.keys(obj);
    for (var i = 0; i < keys.length; i++) {
        var key = keys[i];
        existed[key] = globalThis.hasOwnProperty(key);
        if (existed[key]) prev[key] = globalThis[key];
        globalThis[key] = obj[key];
    }
    obj.__prevGlobals__ = prev;
    obj.__prevKeys__ = keys;
    obj.__prevExisted__ = existed;
    __bindingsStack__.push(obj);
}

function __exitBindings() {
    var obj = __bindingsStack__.pop();
    // 恢复 __enterBindings 写入的 globalThis 属性
    if (obj && obj.__prevKeys__) {
        var prev = obj.__prevGlobals__;
        var keys = obj.__prevKeys__;
        var existed = obj.__prevExisted__;
        for (var i = 0; i < keys.length; i++) {
            var key = keys[i];
            if (existed[key]) {
                globalThis[key] = prev[key];
            } else {
                delete globalThis[key];
            }
        }
    }
}

// ============ Java Class 包装 ============

/**
 * 把 Class 句柄包装为 JS 构造函数 + Proxy。
 */
function __wrapClass(classHandle, path) {
    var dangerousApi = __getDangerousApi();

    var ctor = function() {};
    var proxy = new Proxy(ctor, {
        get: function(target, prop) {
            if (prop === '__classHandle__') return classHandle;
            if (prop === '__java_class_handle__') return classHandle;
            if (prop === '__pkgPath__') return undefined;
            if (prop === 'name') return path;
            if (prop === 'length') return 0;
            if (typeof prop !== 'string') return undefined;
            if (prop === 'toString') return function() { return 'class ' + path; };
            if (prop === 'then') return undefined;
            var field = __getStaticField(classHandle, prop, dangerousApi);
            if (field !== null && field !== undefined) return field;
            var innerClassHandle = __loadJavaClass(path + "$" + prop, dangerousApi);
            if (innerClassHandle > 0) {
                return __wrapClass(innerClassHandle, path + "$" + prop);
            }
            return function() {
                var args = Array.prototype.slice.call(arguments);
                return __callStaticMethod(classHandle, prop, args, dangerousApi);
            };
        },
        construct: function(target, args) {
            if (args.length === 1
                && typeof args[0] === 'object' && args[0] !== null
                && args[0].__classHandle__ === undefined
                && __isInterface(classHandle, dangerousApi)) {
                return JavaAdapter(proxy, args[0]);
            }
            return __newJavaInstance(classHandle, args, dangerousApi);
        },
        apply: function(target, thisArg, args) {
            return __newJavaInstance(classHandle, args, dangerousApi);
        }
    });
    return proxy;
}

// ============ Packages 模拟 ============

function __makePkgProxy(prefix) {
    return new Proxy({}, {
        get: function(target, prop) {
            if (typeof prop !== 'string') return undefined;
            if (prop === '__pkgPath__') return prefix;
            if (prop === '__classHandle__') return undefined;
            if (prop === 'then') return undefined;
            if (prop === Symbol.toPrimitive) return function() { return '[package ' + (prefix || '') + ']'; };
            if (prop === 'toString') return function() { return '[package ' + (prefix || '') + ']'; };
            if (prop === 'valueOf') return function() { return prefix; };
            var path = prefix ? prefix + '.' + prop : prop;
            var dangerousApi = __getDangerousApi();
            var classHandle = __loadJavaClass(path, dangerousApi);
            if (classHandle > 0) {
                return __wrapClass(classHandle, path);
            }
            return __makePkgProxy(path);
        }
    });
}

// 顶级别名直接构造, 跳过 __loadJavaClass('java') 等无效调用 ——
// java/javax/android/org/com/io/cn 都不可能是 Java 类名 (无包路径), 走 Packages.java
// 也只是 __loadJavaClass 返回 0 后 fallback 到 __makePkgProxy('java'), 直接构造省 7 次 JNI 往返。
// 语义等价: 访问 java.SomeClass 仍走 Packages.java 的 Proxy get trap 调用 __loadJavaClass('java.SomeClass')。
var Packages = __makePkgProxy('');
var java = __makePkgProxy('java');
var javax = __makePkgProxy('javax');
var android = __makePkgProxy('android');
var org = __makePkgProxy('org');
var com = __makePkgProxy('com');
var io = __makePkgProxy('io');
var cn = __makePkgProxy('cn');

// ============ JavaImporter ============

function JavaImporter() {
    var classMap = {};
    var packagePaths = [];

    function addClass(cls) {
        if (cls && cls.name) {
            var fullName = cls.name;
            var simpleName = fullName.substring(fullName.lastIndexOf('.') + 1);
            classMap[simpleName] = cls;
        }
    }

    function addPackage(pkg) {
        if (pkg && pkg.__pkgPath__) {
            packagePaths.push(pkg.__pkgPath__);
        }
    }

    Array.prototype.slice.call(arguments).forEach(function(arg) {
        if (arg && arg.__pkgPath__) {
            addPackage(arg);
        } else if (arg && arg.__classHandle__) {
            addClass(arg);
        }
    });

    var importerTarget = {
        importClass: function() {
            Array.prototype.slice.call(arguments).forEach(addClass);
        },
        importPackage: function() {
            Array.prototype.slice.call(arguments).forEach(addPackage);
        }
    };

    return new Proxy(importerTarget, {
        get: function(target, prop) {
            if (typeof prop !== 'string') return undefined;
            if (prop === 'then') return undefined;
            if (target[prop]) return target[prop];
            if (classMap[prop]) return classMap[prop];
            var dangerousApi = __getDangerousApi();
            for (var i = 0; i < packagePaths.length; i++) {
                var fullClassName = packagePaths[i] + '.' + prop;
                var classHandle = __loadJavaClass(fullClassName, dangerousApi);
                if (classHandle > 0) {
                    var cls = __wrapClass(classHandle, fullClassName);
                    classMap[prop] = cls;
                    return cls;
                }
            }
            return undefined;
        },
        has: function(target, prop) {
            if (typeof prop !== 'string') return false;
            if (target[prop] !== undefined) return true;
            if (classMap[prop] !== undefined) return true;
            var dangerousApi = __getDangerousApi();
            for (var i = 0; i < packagePaths.length; i++) {
                if (__classExists(packagePaths[i] + '.' + prop, dangerousApi)) return true;
            }
            return false;
        }
    });
}

function importClass(clazz) {
    if (!clazz || !clazz.name) return;
    var name = clazz.name;
    var simpleName = name.substring(name.lastIndexOf('.') + 1);
    globalThis[simpleName] = clazz;
}

function importPackage(pkg) {
    // no-op: QuickJS 无法枚举 Java 包下所有类
}

// ============ JavaAdapter ============

var __jsFnCounter = 0;

function __registerJsFunction(jsObj) {
    var name = '__jsFn_' + (__jsFnCounter++) + '__';
    globalThis[name] = jsObj;
    var dangerousApi = __getDangerousApi();
    return __registerJsFunctionNative(name, dangerousApi);
}

function JavaAdapter(superClass, implementation) {
    if (!superClass || !superClass.__classHandle__) {
        throw new Error('JavaAdapter requires a Java class/interface as first argument');
    }
    var classHandle = superClass.__classHandle__;
    var jsFnHandle = __registerJsFunction(implementation);
    var dangerousApi = __getDangerousApi();
    var adapter = __newJavaAdapter(classHandle, jsFnHandle, dangerousApi);
    if (adapter === null || adapter === undefined) {
        throw new Error('Failed to create JavaAdapter');
    }
    return adapter;
}

// __getDangerousApi 由 QuickJsNative.nativeDefineBinding 注册为 native binding, 从 ctx opaque 读值,
// 这里不定义 JS stub —— top-level function 声明会覆盖 globalThis 上的同名属性, 让 native binding 失效.
    """.trimIndent()
}
