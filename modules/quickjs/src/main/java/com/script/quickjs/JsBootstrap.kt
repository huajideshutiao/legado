package com.script.quickjs

/**
 * JS bootstrap 脚本 (架构 A: 极简版本)。
 *
 * 在 QuickJs 实例创建后立即 evaluate,注入:
 * 1. `Packages` / `java` / `javax` / `android` / `com` / `org` / `io` / `cn`
 *    —— 用 ES6 Proxy 模拟动态路径访问,按 `prefix.prop` 累积路径,
 *    调用 native binding `__loadJavaClass` 判断是 Class 还是 Package。
 * 2. `JavaImporter` —— 接受多个 Class,在 `with` 语句里按简单名查找。
 * 3. `importClass` / `importPackage` —— 全局函数。
 * 4. `JavaAdapter` —— 调用 native binding `__newJavaAdapter` 创建 java.lang.reflect.Proxy。
 * 5. `__wrapClass` —— Class 句柄的 JS 包装 (构造/静态成员)。
 *
 * 架构 A 与旧版的区别:
 * - 不再需要 `__wrapJavaObject` / `__wrapJavaMap`: native exotic trap 已实现属性访问
 * - 不再需要 `__makeMethodCallable` / `__makeCallableValue`: native 层在 getProperty trap
 *   中创建 method callable (JS_NewCFunctionData)
 * - 不再需要 `__wrapJavaResult` / `__toPlainArg` / `__toPlainArgs`:
 *   binding 返回值由 native 层 JniValueConvert.fromJavaObject 自动包装为 JavaObject
 * - 不再需要 `__getJavaProperty` / `__getInstanceField` / `__callInstanceMethod` /
 *   `__getInstanceKeys` / `__setInstanceField` / `__hasInstanceMethod`:
 *   这些都由 native exotic trap 直接处理 (JavaObjectBridgeNative.hasProperty/getPropertyInfo/
 *   setProperty/getPropertyNames + method callable 回调)
 *
 * 依赖的 native binding (由 QuickJsEngine.nativeDefineBinding 注册):
 * - `__loadJavaClass(fullName, dangerousApi): Long` —— 返回 classHandle (JS Number)
 * - `__classExists(fullName, dangerousApi): Boolean`
 * - `__isInterface(classHandle, dangerousApi): Boolean`
 * - `__newJavaInstance(classHandle, args, dangerousApi): Any?` —— 返回 JavaObject (native 自动包装)
 * - `__callStaticMethod(classHandle, methodName, args, dangerousApi): Any?` —— 返回 JavaObject
 * - `__getStaticField(classHandle, fieldName, dangerousApi): Any?` —— 返回 JavaObject
 * - `__setStaticField(classHandle, fieldName, value, dangerousApi): Boolean`
 * - `__newJavaAdapter(classHandle, jsFnHandle, dangerousApi): Any?` —— 返回 JavaObject
 * - `__registerJsFunctionNative(jsObjectExpr, dangerousApi): Long` —— 返回 JsFunctionHandle 句柄
 * - `__wrapJavaHandle(handle): Any?` —— 把 Java 对象句柄转换为 JavaObject (供 JsFunctionHandle 用)
 *
 * 全局状态变量(每次 evalJS 前由 QuickJsEngine 设置):
 * - `__dangerousApi__` —— 是否旁路安全名单 (用于 binding 调用时传参)
 */
object JsBootstrap {

    val code: String = """
// ============ 全局状态 ============
var __dangerousApi__ = false;

// ============ Java Class 包装 ============

/**
 * 把 Class 句柄包装为 JS 构造函数 + Proxy。
 *
 * - new Class(args) 实例化对象 (走 __newJavaInstance binding, 返回 JavaObject)
 * - Class.staticMethod(args) 调用静态方法 (走 __callStaticMethod binding, 返回 JavaObject)
 * - Class.staticField 访问静态字段 (走 __getStaticField binding, 返回 JavaObject)
 *
 * 与旧版区别: 不再调用 __wrapJavaObject 包装返回值,
 * 因为 native 层 JniValueConvert.fromJavaObject 已自动把 Java 对象包装为 JavaObject。
 *
 * 静态方法返回的是一个 JS function, 调用时走 __callStaticMethod binding。
 * 注意: 静态方法不存在时仍返回 function (调用时 binding 返回 null),
 * 这与 rhino LiveConnect 行为一致 (静态字段优先, 找不到才返回 method callable)。
 */
function __wrapClass(classHandle, path) {
    var ctor = function() {};
    var proxy = new Proxy(ctor, {
        get: function(target, prop) {
            if (prop === '__classHandle__') return classHandle;
            // 兼容旧 bootstrap 的 __java_class_handle__ 别名 (部分代码可能仍引用)
            if (prop === '__java_class_handle__') return classHandle;
            // 显式返回 undefined,避免 Package 标识属性误判 (JavaImporter 用于区分 Class/Package)
            if (prop === '__pkgPath__') return undefined;
            if (prop === 'name') return path;
            if (prop === 'length') return 0;
            if (typeof prop !== 'string') return undefined;
            if (prop === 'toString') return function() { return 'class ' + path; };
            // 避免 await 触发 then 检查
            if (prop === 'then') return undefined;
            // 尝试静态字段 (binding 返回 JavaObject 或基本类型, null/undefined 表示字段不存在)
            var field = __getStaticField(classHandle, prop, __dangerousApi__);
            if (field !== null && field !== undefined) return field;
            // 尝试静态方法: 返回一个 JS function, 调用时走 __callStaticMethod binding
            // 参数中的 JavaObject 由 native 层 toJavaObject 自动解包为原始 Java 对象
            return function() {
                var args = Array.prototype.slice.call(arguments);
                return __callStaticMethod(classHandle, prop, args, __dangerousApi__);
            };
        },
        construct: function(target, args) {
            // JavaAdapter 语法: new Interface({method: fn}) (用于 new View.OnClickListener({onClick: function(){}}))
            // 仅当 Class 是 interface 且参数是单个 JS 对象 (非 JavaObject/Class) 时走 JavaAdapter 路径,
            // 否则按普通构造器实例化 (避免误判 Map/Java 对象参数)
            //
            // 注意: JavaObject 在 JS 侧通过 exotic trap 暴露属性, 没有 __java_handle__ 字面量属性,
            // 这里用 typeof + __classHandle__ 检测区分。
            if (args.length === 1
                && typeof args[0] === 'object' && args[0] !== null
                && args[0].__classHandle__ === undefined
                && __isInterface(classHandle, __dangerousApi__)) {
                // 必须传 proxy 而非 target, 让 JavaAdapter 内部通过 proxy.__classHandle__ 触发 get trap
                return JavaAdapter(proxy, args[0]);
            }
            // 普通实例化: __newJavaInstance 返回 JavaObject
            return __newJavaInstance(classHandle, args, __dangerousApi__);
        },
        apply: function(target, thisArg, args) {
            // Class() 作为函数调用, 等价于 new Class(args)
            return __newJavaInstance(classHandle, args, __dangerousApi__);
        }
    });
    return proxy;
}

// ============ Packages 模拟 ============

/**
 * 创建一个 Package Proxy,按前缀累积路径。
 * 访问 prop 时:
 *   1. 拼接 prefix.prop
 *   2. 尝试 __loadJavaClass,成功则返回 __wrapClass(classHandle, path) 包装
 *   3. 失败则返回下一级 Package Proxy
 */
function __makePkgProxy(prefix) {
    return new Proxy({}, {
        get: function(target, prop) {
            if (typeof prop !== 'string') return undefined;
            // 暴露 __pkgPath__ 供 JavaImporter.importPackage 读取包路径
            if (prop === '__pkgPath__') return prefix;
            // 显式返回 undefined,避免 Class 标识属性误判 (JavaImporter 用于区分 Class/Package)
            if (prop === '__classHandle__') return undefined;
            if (prop === 'then') return undefined;
            if (prop === Symbol.toPrimitive) return function() { return '[package ' + (prefix || '') + ']'; };
            if (prop === 'toString') return function() { return '[package ' + (prefix || '') + ']'; };
            if (prop === 'valueOf') return function() { return prefix; };
            var path = prefix ? prefix + '.' + prop : prop;
            // 尝试加载为 Class
            var classHandle = __loadJavaClass(path, __dangerousApi__);
            if (classHandle > 0) {
                return __wrapClass(classHandle, path);
            }
            // 不是 Class, 继续嵌套
            return __makePkgProxy(path);
        }
    });
}

// 注入 Packages 和快捷别名 (与 rhino LiveConnect 一致)
var Packages = __makePkgProxy('');
var java = __makePkgProxy('java');
var javax = __makePkgProxy('javax');
var android = __makePkgProxy('android');
var com = __makePkgProxy('com');
var org = __makePkgProxy('org');
var io = __makePkgProxy('io');
var cn = __makePkgProxy('cn');

// ============ JavaImporter ============

/**
 * JavaImporter 接受多个 Class/Package,在 with 语句里按简单名查找。
 *
 * 支持 rhino 的两种用法:
 *   1. new JavaImporter(Class1, Class2, Package1, ...)
 *   2. var imp = new JavaImporter(); imp.importClass(Class); imp.importPackage(Package);
 *      with(imp) { SimpleName.staticMethod(...); }
 *
 * Class 按简单名直接映射。
 * Package 记录路径,在 with 查找时动态 __loadJavaClass(pkgPath + '.' + simpleName) 加载。
 * (QuickJS 无法枚举 Java 包下所有类,只能按需动态加载)
 */
function JavaImporter() {
    var classMap = {};
    var packagePaths = [];

    function addClass(cls) {
        if (cls && cls.name) {
            // 注意: 必须先取出 fullName 再调 lastIndexOf,否则 cls.lastIndexOf('.') 会触发
            // __wrapClass Proxy 的 get('lastIndexOf'),返回静态方法调用函数而非字符串查找,
            // 导致 simpleName 计算错误 (如 'java.lang.String' 误算为 'ava.lang.String')
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

    // 处理构造参数 (Class 或 Package)
    // 注意: 必须先检查 __pkgPath__ (Package),再检查 __classHandle__ (Class),
    // 因为 Proxy 的 get 对未知属性会返回 truthy 值 (如嵌套 Package Proxy 或 function)
    Array.prototype.slice.call(arguments).forEach(function(arg) {
        if (arg && arg.__pkgPath__) {
            addPackage(arg);
        } else if (arg && arg.__classHandle__) {
            addClass(arg);
        }
    });

    // importer 对象本身的方法 (target of Proxy)
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
            // 先查 importer 自身方法 (importClass/importPackage)
            if (target[prop]) return target[prop];
            // 再查已导入的 Class map
            if (classMap[prop]) return classMap[prop];
            // 最后动态加载已导入包下的类 (按 simpleName 拼接 pkgPath + '.' + prop)
            for (var i = 0; i < packagePaths.length; i++) {
                var fullClassName = packagePaths[i] + '.' + prop;
                var classHandle = __loadJavaClass(fullClassName, __dangerousApi__);
                if (classHandle > 0) {
                    var cls = __wrapClass(classHandle, fullClassName);
                    classMap[prop] = cls;  // 缓存,避免重复加载
                    return cls;
                }
            }
            return undefined;
        },
        has: function(target, prop) {
            if (typeof prop !== 'string') return false;
            if (target[prop] !== undefined) return true;
            if (classMap[prop] !== undefined) return true;
            // 检查已导入包下是否有此类 (用 __classExists 探测,不注册句柄,避免 has 泄漏)
            // 真正访问该属性时,get trap 会调用 __loadJavaClass 注册并缓存到 classMap
            for (var i = 0; i < packagePaths.length; i++) {
                if (__classExists(packagePaths[i] + '.' + prop, __dangerousApi__)) return true;
            }
            return false;
        }
    });
}

/**
 * importClass(clazz) —— 把 Class 按简单名导入到全局作用域。
 */
function importClass(clazz) {
    if (!clazz || !clazz.name) return;
    var name = clazz.name;
    var simpleName = name.substring(name.lastIndexOf('.') + 1);
    globalThis[simpleName] = clazz;
}

/**
 * importPackage(pkg) —— Rhino 行为:导入包下所有类。
 * QuickJS 无法枚举包下所有类,这里 no-op。
 */
function importPackage(pkg) {
    // no-op: QuickJS 无法枚举 Java 包下所有类
}

// ============ JavaAdapter ============

// JS function 计数器,用于为 JavaAdapter 回调对象生成唯一全局名
var __jsFnCounter = 0;

/**
 * 注册 JS function 对象,返回 Kotlin 侧 JsFunctionHandle 句柄。
 *
 * 把 JS 对象存到全局变量 (避免被 GC),通过唯一名让 Kotlin 侧按需取回调用。
 * 调用 __registerJsFunctionNative binding, 由 BindingHandler 分发到
 * JsFunctionHandle.register(ctxPtr, name, dangerousApi)。
 */
function __registerJsFunction(jsObj) {
    var name = '__jsFn_' + (__jsFnCounter++) + '__';
    globalThis[name] = jsObj;
    return __registerJsFunctionNative(name, __dangerousApi__);
}

/**
 * JavaAdapter(superClass, implementation) —— 创建实现 Java 接口的代理对象。
 * superClass 是 __wrapClass 返回的 Proxy,implementation 是 JS 对象 { methodName: function() {...} }。
 *
 * 返回值: native 层 __newJavaAdapter binding 返回原始 Java 代理对象,
 * 由 JniValueConvert.fromJavaObject 自动包装为 JavaObject, JS 侧可直接访问。
 */
function JavaAdapter(superClass, implementation) {
    if (!superClass || !superClass.__classHandle__) {
        throw new Error('JavaAdapter requires a Java class/interface as first argument');
    }
    var classHandle = superClass.__classHandle__;
    var jsFnHandle = __registerJsFunction(implementation);
    var adapter = __newJavaAdapter(classHandle, jsFnHandle, __dangerousApi__);
    if (adapter === null || adapter === undefined) {
        throw new Error('Failed to create JavaAdapter');
    }
    return adapter;
}
    """.trimIndent()
}
