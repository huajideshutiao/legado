package com.script.quickjs

/**
 * JS bootstrap 脚本。
 *
 * 在 QuickJs 实例创建后立即 evaluate,注入:
 * 1. `Packages` / `java` / `javax` / `android` / `com` / `org` —— 用 ES6 Proxy 模拟动态路径访问,
 *    按 `prefix.prop` 累积路径,调用 Kotlin 侧 `__loadJavaClass` 判断是 Class 还是 Package。
 * 2. `JavaImporter` —— 接受多个 Class,在 `with` 语句里按简单名查找。
 * 3. `importClass` / `importPackage` —— 全局函数。
 * 4. `JavaAdapter` —— 调用 Kotlin 侧 `__newJavaAdapter` 创建 java.lang.reflect.Proxy。
 * 5. `__wrapClass` / `__wrapJavaObject` —— Java 句柄的 JS 包装。
 *
 * 依赖的 Kotlin function binding(由 QuickJsEngine 注册):
 * - `__loadJavaClass(fullName, dangerousApi): Long`
 * - `__classExists(fullName, dangerousApi): Boolean` —— 仅检查类是否存在,不注册句柄(避免 has 泄漏)
 * - `__isInterface(classHandle, dangerousApi): Boolean` —— 判断 Class 是否为 interface,用于 new Interface(impl) 语法检测
 * - `__newJavaInstance(classHandle, args, dangerousApi): Long`
 * - `__callStaticMethod(classHandle, methodName, args, dangerousApi): Any?`
 * - `__getStaticField(classHandle, fieldName, dangerousApi): Any?`
 * - `__callInstanceMethod(objHandle, methodName, args, dangerousApi): Any?`
 * - `__getInstanceField(objHandle, fieldName, dangerousApi): Any?`
 * - `__hasInstanceMethod(objHandle, methodName, dangerousApi): Boolean` —— 检查实例方法是否存在(不触发调用),用于 field+method 同名时判断 method 优先级
 * - `__getInstanceKeys(objHandle, dangerousApi): String[]` —— 返回 Java 对象所有可枚举属性名,用于 Proxy ownKeys trap (Object.entries/Object.keys/for...in)
 * - `__setInstanceField(objHandle, fieldName, value, dangerousApi): Boolean`
 * - `__newJavaAdapter(classHandle, jsFnHandle, dangerousApi): Long`
 * - `__registerJsFunction(jsObjectExpr, dangerousApi): Long`
 *
 * 全局状态变量(每次 evalJS 前由 QuickJsEngine 设置):
 * - `__dangerousApi__` —— 是否旁路安全名单
 */
object JsBootstrap {

    val code: String = """
// ============ 全局状态 ============
var __dangerousApi__ = false;

// ============ Java 对象包装 ============

/**
 * 包装 binding 返回值。
 *
 * Kotlin 侧返回的 Java 对象是 { __java_handle__: handle } Map,
 * JS 侧无法直接调用其实例方法,需要转为 __wrapJavaObject Proxy。
 * 此函数检测并转换,基本类型(String/Number/Boolean/List)原样返回。
 *
 * Map 类型 (标记 __java_is_map__) 走 __wrapJavaMap 路径,
 * 通过 ownKeys trap 支持 for...in/Object.entries,且 get/put/set 直接操作原 Java Map (真正互操作)。
 */
function __wrapJavaResult(v) {
    if (v === null || v === undefined) return v;
    // 数组/列表: 递归包装每个元素为 Java Proxy
    if (Array.isArray(v)) {
        return v.map(__wrapJavaResult);
    }
    // quickjs-kt 把 Kotlin Map 转成 JS Map (ES6 Map)
    if (v instanceof Map) {
        var handle = v.get('__java_handle__');
        if (handle !== undefined && handle !== null) {
            // Map 句柄: 用 __wrapJavaMap 包装,支持 for...in + 真正互操作
            // (get/put/set 直接操作原 Java Map,而非 quickjs-kt 创建的 ES6 Map 副本)
            if (v.get('__java_is_map__') === true) {
                return __wrapJavaMap(handle);
            }
            // 普通 Java 对象句柄
            return __wrapJavaObject(handle);
        }
    }
    // 基本类型(String/Number/Boolean) 或无 __java_handle__ 的对象原样返回
    return v;
}

/**
 * 把 field 值包装为 FieldAndMessages 风格的 callable(参考 rhino FieldAndMethods)。
 *
 * 背景: StrResponse 同时有 `var body: String?` field 和 `fun body() = body` method,
 * rhino LiveConnect 在 field+method 同名时返回 FieldAndMethods(继承 NativeJavaMethod):
 * - `obj.body()` → 调用 NativeJavaMethod.call → 执行 Java method → 返回 body String
 * - `obj.body + ""` → toPrimitive → getDefaultValue → 返回 field 值
 * - `obj.body.method()` → 属性委托到 field 值的原型方法
 *
 * 本函数模拟此行为,只在 `__hasInstanceMethod` 为 true 且 field 存在时调用:
 * - `wrapper()` → 执行 Java method(不返回 field 值,与 rhino 一致)
 * - `wrapper(args)` → 执行 Java method(带参)
 * - `Symbol.toPrimitive` → 返回 field 值(兼容 `obj.body + ""` 等隐式转换)
 * - `wrapper.startsWith(...)` → 委托到 field 值的 String.prototype.startsWith,
 *   `this` 绑定为 field 值
 *
 * 对非基本类型(String/Number/Boolean 之外)原样返回,不包装。
 */
function __makeCallableValue(v, objHandle, propName) {
    if (v === null || v === undefined) return v;
    var t = typeof v;
    if (t !== 'string' && t !== 'number' && t !== 'boolean') return v;
    var fn = function() {
        // 调用时执行 Java method(与 rhino FieldAndMethods.call 一致)
        var args = Array.prototype.slice.call(arguments);
        var plainArgs = __toPlainArgs(args);
        return __wrapJavaResult(__callInstanceMethod(objHandle, propName, plainArgs, __dangerousApi__));
    };
    return new Proxy(fn, {
        get: function(target, prop) {
            // toPrimitive 返回 field 值(与 rhino FieldAndMethods.getDefaultValue 一致)
            if (prop === Symbol.toPrimitive) {
                return function() { return v; };
            }
            // 委托到 field 值的属性,String/Number/Boolean 的原型方法都能正常访问
            var val = v[prop];
            if (typeof val === 'function') {
                // bind(v) 确保原型方法调用时 this 是 field 值,而非 wrapper
                return val.bind(v);
            }
            return val;
        }
    });
}

/**
 * 把 JS 参数转为 binding 能识别的普通值。
 *
 * JS Proxy(Java 对象/Class 包装)传给 binding 时,quickjs-kt 无法识别,
 * 需要解引用为 { __java_handle__: handle } 或 { __java_class_handle__: handle }。
 * 基本类型原样返回。
 */
function __toPlainArg(v) {
    if (v && typeof v === 'object') {
        var handle = v.__java_handle__;
        if (handle !== undefined) {
            return { __java_handle__: handle };
        }
        var classHandle = v.__classHandle__;
        if (classHandle !== undefined) {
            return { __java_class_handle__: classHandle };
        }
    }
    return v;
}

/**
 * 把参数数组里所有 Proxy 解引用为普通值。
 */
function __toPlainArgs(args) {
    return args.map(function(a) { return __toPlainArg(a); });
}

/**
 * 把 Java Map 句柄包装为 JS Proxy,支持真正的互操作。
 *
 * 与 __wrapJavaObject 的区别: 增加 ownKeys/getOwnPropertyDescriptor trap,
 * 支持 for...in / Object.entries / Object.keys 枚举 Map keys。
 *
 * 互操作 (直接操作原 Java Map,非 ES6 Map 副本):
 * - body[key] / body.get(key) → Map.get(key)  (经 __getInstanceField → getCollectionField)
 * - body[key] = val → Map.put(key, val)      (经 __setInstanceField → setCollectionField)
 * - body.put(key, val) → Map.put(key, val)   (经 __callInstanceMethod)
 * - body.size() → Map.size()                  (经 __callInstanceMethod)
 * - for (let key in body) → Map.keySet()      (经 __getInstanceKeys)
 * - Object.entries(body) → ownKeys + getOwnPropertyDescriptor
 *
 * ownKeys 必须包含 __java_handle__ (non-enumerable),
 * 否则 native 层 JS→Kotlin 转换时丢失 handle,unwrapReturnValue 解包失败。
 * for...in / Object.keys 只返回 enumerable 属性,不会看到 __java_handle__。
 */
function __wrapJavaMap(objHandle) {
    if (objHandle === 0 || objHandle === null) return null;
    // 缓存 Map keys (ownKeys 时填充, set trap 时更新)
    var cachedKeys = null;
    function getMapKeys() {
        if (cachedKeys === null) {
            var mapKeys = __getInstanceKeys(objHandle, __dangerousApi__) || [];
            cachedKeys = ['__java_handle__'].concat(mapKeys);
        }
        return cachedKeys;
    }
    return new Proxy({ __java_handle__: objHandle }, {
        get: function(target, prop) {
            if (prop === '__java_handle__') return objHandle;
            // toPrimitive: 调用 Java toString()
            if (prop === Symbol.toPrimitive) {
                return function() {
                    var s = __callInstanceMethod(objHandle, 'toString', [], __dangerousApi__);
                    return s === null ? '[java@' + objHandle + ']' : __wrapJavaResult(s);
                };
            }
            if (typeof prop !== 'string') return undefined;
            if (prop === 'then') return undefined;
            // __getJavaPropertyValue 已对 Map 做特判:
            // - __getInstanceField → getCollectionField → Map.get(key) 返回值
            // - __hasInstanceMethod → get/put/size/containsKey 等方法 callable
            return __getJavaPropertyValue(objHandle, prop);
        },
        set: function(target, prop, value) {
            // body[key] = val → __setInstanceField → setCollectionField → Map.put(key, val)
            // 真正修改原 Java Map
            if (typeof prop === 'string') {
                __setInstanceField(objHandle, prop, __toPlainArg(value), __dangerousApi__);
                if (cachedKeys !== null && cachedKeys.indexOf(prop) === -1) {
                    cachedKeys.push(prop);
                }
            }
            return true;
        },
        ownKeys: function() {
            return getMapKeys();
        },
        getOwnPropertyDescriptor: function(target, prop) {
            if (prop === '__java_handle__') {
                return { value: objHandle, writable: false, enumerable: false, configurable: true };
            }
            var keys = getMapKeys();
            if (keys.indexOf(prop) === -1) return undefined;
            return {
                value: __getJavaPropertyValue(objHandle, prop),
                writable: true,
                enumerable: true,
                configurable: true
            };
        }
    });
}

/**
 * 获取 Java 对象的属性值(模拟 rhino LiveConnect 成员查找)。
 * 供 __wrapJavaObject 的 get trap 和 getOwnPropertyDescriptor trap 共用。
 *
 * 查找顺序:
 *   1. 尝试 field/getter(对应 rhino BeanProperty / NativeJavaField)
 *      Java 侧 getInstanceField 已对 Map/List/Array 的 length/索引做了特判
 *   2. 若 field 存在且同名 method 也存在 → 返回 FieldAndMessages 风格 callable
 *   3. 若只有 field/getter → 返回值
 *   4. 若 field 不存在 → 检查同名 method → 返回 method callable 或 undefined
 */
function __getJavaPropertyValue(objHandle, prop) {
    // 先尝试 field/getter(对应 rhino 的 BeanProperty / NativeJavaField)
    // Java 侧 getInstanceField 已对 Map/List/Array 的 length/索引做了特判,这里不重复处理
    var field = __wrapJavaResult(__getInstanceField(objHandle, prop, __dangerousApi__));
    if (field !== null && field !== undefined) {
        // field 存在,检查是否有同名 method (field+method 同名场景,如 StrResponse.body)
        // 与 rhino LiveConnect 行为一致: method 优先,返回 FieldAndMessages 风格 callable
        if (__hasInstanceMethod(objHandle, prop, __dangerousApi__)) {
            return __makeCallableValue(field, objHandle, prop);
        }
        return field;
    }
    // field 不存在,检查是否有同名 method
    // - 有 method → 返回 method callable (如 list.add, map.size, str.substring)
    // - 无 method → 返回 undefined (与 rhino NativeJavaMap 行为一致:
    //   missing key 不在 Map 中时返回 NOT_FOUND,JS 侧 typeof 为 "undefined")
    if (__hasInstanceMethod(objHandle, prop, __dangerousApi__)) {
        return function() {
            var args = Array.prototype.slice.call(arguments);
            var plainArgs = __toPlainArgs(args);
            return __wrapJavaResult(__callInstanceMethod(objHandle, prop, plainArgs, __dangerousApi__));
        };
    }
    return undefined;
}

/**
 * 把 Java 对象句柄包装为 JS Proxy。
 * JS 访问 prop 时(模拟 rhino LiveConnect 的成员查找):
 *   1. 尝试 field/getter(对应 rhino BeanProperty / NativeJavaField)
 *   2. 若 field 存在且同名 method 也存在 → 返回 FieldAndMessages 风格 callable
 *      (调用执行 method,Symbol.toPrimitive 返回 field 值,属性委托到 field 值)
 *   3. 若只有 field/getter → 返回值
 *   4. 若 field 不存在 → 检查是否有同名 method:
 *      - 有 → 返回 method callable (如 list.add, map.size, str.substring)
 *      - 无 → 返回 undefined (与 rhino NativeJavaMap.getIds() 行为一致:
 *        Map 不含 key 时返回 NOT_FOUND,JS 侧 typeof 为 "undefined")
 *
 * 不加 ownKeys/getOwnPropertyDescriptor trap:
 * native 层在 evaluate 返回值时,会把 JS Proxy 转成 Kotlin Map(通过 JS_GetOwnPropertyNames
 * + JS_GetOwnProperty 遍历 own properties)。若 trap 返回 Java 对象的所有 method/field name,
 * 转换后的 Map 不再包含 __java_handle__ 键,QuickJsEngine.unwrapReturnValue 的解包逻辑失效
 * (无法识别为 Java 对象句柄,会原样返回 Map,导致 toString() 得到 JsObject@xxx 而非 String)。
 *
 * Map 类型已通过 __wrapJavaMap 单独处理(在 __wrapJavaResult 检测 __java_is_map__ 标记时调用),
 * 支持 for...in/Object.entries/Object.keys 且 get/put/set 直接操作原 Java Map。普通 Java 对象
 * (如 String/Number/自定义业务对象) 业务代码不需要枚举其属性,故不启用 ownKeys trap。
 */
function __wrapJavaObject(objHandle) {
    if (objHandle === 0 || objHandle === null) return null;
    return new Proxy({ __java_handle__: objHandle }, {
        get: function(target, prop) {
            if (prop === '__java_handle__') return objHandle;
            // toPrimitive: 调用 Java toString(),与 rhino LiveConnect 一致(避免 String(java) 返回 [java@handle])
            if (prop === Symbol.toPrimitive) {
                return function() {
                    var s = __callInstanceMethod(objHandle, 'toString', [], __dangerousApi__);
                    return s === null ? '[java@' + objHandle + ']' : __wrapJavaResult(s);
                };
            }
            if (typeof prop !== 'string') return undefined;
            if (prop === 'then') return undefined;
            // toString/valueOf 不做特殊覆盖,走正常实例方法路径,保证调用 Java 对象的真实 toString
            return __getJavaPropertyValue(objHandle, prop);
        },
        set: function(target, prop, value) {
            if (typeof prop === 'string') {
                __setInstanceField(objHandle, prop, __toPlainArg(value), __dangerousApi__);
            }
            return true;
        }
    });
}

/**
 * 把 Class 句柄包装为 JS 构造函数 + Proxy。
 * - new Class(args) 实例化对象
 * - Class.staticMethod(args) 调用静态方法
 * - Class.staticField 访问静态字段
 */
function __wrapClass(classHandle, path) {
    var ctor = function(args) {
        args = args || [];
        var plainArgs = __toPlainArgs(args);
        var objHandle = __newJavaInstance(classHandle, plainArgs, __dangerousApi__);
        if (objHandle === 0) throw new Error('Failed to instantiate ' + path);
        return __wrapJavaObject(objHandle);
    };
    var proxy = new Proxy(ctor, {
        get: function(target, prop) {
            if (prop === '__classHandle__') return classHandle;
            if (prop === '__java_class_handle__') return classHandle;
            // 显式返回 undefined,避免 Package 标识属性误判(JavaImporter 用于区分 Class/Package)
            if (prop === '__pkgPath__') return undefined;
            if (prop === 'name') return path;
            if (prop === 'length') return 0;
            if (typeof prop !== 'string') return undefined;
            if (prop === 'toString') return function() { return 'class ' + path; };
            // 尝试静态字段
            var field = __wrapJavaResult(__getStaticField(classHandle, prop, __dangerousApi__));
            if (field !== null && field !== undefined) return field;
            // 尝试静态方法
            // 注意: 参数需经 __toPlainArgs 解引用
            return function() {
                var args = Array.prototype.slice.call(arguments);
                var plainArgs = __toPlainArgs(args);
                return __wrapJavaResult(__callStaticMethod(classHandle, prop, plainArgs, __dangerousApi__));
            };
        },
        construct: function(target, args) {
            // JavaAdapter 语法: new Interface({method: fn}) (用于 new View.OnClickListener({onClick: function(){}}))
            // 仅当 Class 是 interface 且参数是单个 JS 对象(非 Java 句柄/Class)时走 JavaAdapter 路径,
            // 否则按普通构造器实例化(避免误判 Map/Java 对象参数)
            if (args.length === 1
                && typeof args[0] === 'object' && args[0] !== null
                && args[0].__java_handle__ === undefined
                && args[0].__classHandle__ === undefined
                && __isInterface(classHandle, __dangerousApi__)) {
                // 注意: 必须传 proxy 而非 target。target 是被代理的 ctor function,
                // 访问 target.__classHandle__ 不会触发 Proxy.get,会返回 undefined。
                // 传 proxy 让 JavaAdapter 内部通过 proxy.__classHandle__ 触发 get handler 取到 classHandle。
                return JavaAdapter(proxy, args[0]);
            }
            var plainArgs = __toPlainArgs(args);
            var objHandle = __newJavaInstance(classHandle, plainArgs, __dangerousApi__);
            if (objHandle === 0) throw new Error('Failed to instantiate ' + path);
            return __wrapJavaObject(objHandle);
        },
        apply: function(target, thisArg, args) {
            var plainArgs = __toPlainArgs(args);
            var objHandle = __newJavaInstance(classHandle, plainArgs, __dangerousApi__);
            if (objHandle === 0) throw new Error('Failed to instantiate ' + path);
            return __wrapJavaObject(objHandle);
        }
    });
    return proxy;
}

// ============ Packages 模拟 ============

/**
 * 创建一个 Package Proxy,按前缀累积路径。
 * 访问 prop 时:
 *   1. 拼接 prefix.prop
 *   2. 尝试 __loadJavaClass,成功则返回 Class 包装
 *   3. 失败则返回下一级 Package Proxy
 */
function __makePkgProxy(prefix) {
    return new Proxy({}, {
        get: function(target, prop) {
            if (typeof prop !== 'string') return undefined;
            // 暴露 __pkgPath__ 供 JavaImporter.importPackage 读取包路径
            if (prop === '__pkgPath__') return prefix;
            // 显式返回 undefined,避免 Class 标识属性误判(JavaImporter 用于区分 Class/Package)
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
            // 不是 Class,继续嵌套
            return __makePkgProxy(path);
        }
    });
}

// 注入 Packages 和快捷别名
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
            // 导致 simpleName 计算错误(如 'java.lang.String' 误算为 'ava.lang.String')
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

    // 处理构造参数(Class 或 Package)
    // 注意: 必须先检查 __pkgPath__(Package),再检查 __classHandle__(Class),
    // 因为 Proxy 的 get 对未知属性会返回 truthy 值(如嵌套 Package Proxy 或 function)
    Array.prototype.slice.call(arguments).forEach(function(arg) {
        if (arg && arg.__pkgPath__) {
            addPackage(arg);
        } else if (arg && arg.__classHandle__) {
            addClass(arg);
        }
    });

    // importer 对象本身的方法(target of Proxy)
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
            // 先查 importer 自身方法(importClass/importPackage)
            if (target[prop]) return target[prop];
            // 再查已导入的 Class map
            if (classMap[prop]) return classMap[prop];
            // 最后动态加载已导入包下的类(按 simpleName 拼接 pkgPath + '.' + prop)
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
            // 检查已导入包下是否有此类(用 __classExists 探测,不注册句柄,避免 has 泄漏)
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
 * 把 JS 对象存到全局变量(避免被 GC),通过唯一名让 Kotlin 侧按需取回调用。
 */
function __registerJsFunction(jsObj) {
    var name = '__jsFn_' + (__jsFnCounter++) + '__';
    globalThis[name] = jsObj;
    return __registerJsFunctionNative(name, __dangerousApi__);
}

/**
 * JavaAdapter(superClass, implementation) —— 创建实现 Java 接口的代理对象。
 * superClass 是 __wrapClass 返回的 Proxy,implementation 是 JS 对象 { methodName: function() {...} }。
 */
function JavaAdapter(superClass, implementation) {
    if (!superClass || !superClass.__classHandle__) {
        throw new Error('JavaAdapter requires a Java class/interface as first argument');
    }
    var classHandle = superClass.__classHandle__;
    var jsFnHandle = __registerJsFunction(implementation);
    var adapterHandle = __newJavaAdapter(classHandle, jsFnHandle, __dangerousApi__);
    if (adapterHandle === 0) throw new Error('Failed to create JavaAdapter');
    return __wrapJavaObject(adapterHandle);
}

// ============ __keys 全局函数 ============

/**
 * 枚举对象的属性名(与 rhino NativeJavaObject.getIds() 行为一致)。
 *
 * 背景: __wrapJavaObject 不能添加 ownKeys/getOwnPropertyDescriptor trap,
 * 因为 native 层转换时会自动遍历 Proxy 属性导致无限递归 StackOverflowError。
 * 业务层如需枚举 Java 对象属性(类似 Object.entries(javaObj)),
 * 应显式调用 __keys(javaObj) 而非 Object.keys。
 *
 * 行为:
 * - Java Proxy (有 __java_handle__): 调 __getInstanceKeys 返回 Java 对象属性名
 *   - Map: 返回 keySet() (key 转 String)
 *   - List/Array: 返回 0 until size 的字符串索引
 *   - 普通 Java 对象: 返回 public method + public field name (去重)
 * - 普通 JS 对象: 委托到 Object.keys(obj)
 * - 基本类型/null: 返回空数组
 */
function __keys(obj) {
    if (obj === null || obj === undefined) return [];
    if (obj && typeof obj === 'object' && obj.__java_handle__ !== undefined) {
        return __getInstanceKeys(obj.__java_handle__, __dangerousApi__);
    }
    if (typeof obj === 'object') {
        return Object.keys(obj);
    }
    return [];
}
    """.trimIndent()
}
