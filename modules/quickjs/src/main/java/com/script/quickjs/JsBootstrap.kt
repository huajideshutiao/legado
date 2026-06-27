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
 * 5. `__wrapClass` / `__wrapJavaObject` / `__unwrapJavaHandle` —— Java 句柄的 JS 包装。
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
 * 此函数检测并转换,基本类型(String/Number/Boolean/List/Map)原样返回。
 *
 * 注意: quickjs-kt 可能把 Kotlin Map 转为 JS Map(ES6 Map,而非 Object)。
 * JS Map 的内容不是 own properties,Object.entries/keys/for...in 无法枚举,
 * 与 rhino NativeJavaMap.getIds() 行为不一致。
 * 这里把 JS Map 转成 JS Object,使属性枚举能正常工作 (与 rhino 行为一致)。
 */
function __wrapJavaResult(v) {
    if (v === null || v === undefined) return v;
    // 数组/列表: 递归包装每个元素为 Java Proxy
    if (Array.isArray(v)) {
        return v.map(__wrapJavaResult);
    }
    // quickjs-kt 可能把 Kotlin Map 转成 JS Map (ES6 Map)
    // JS Map 的内容不是 own properties,Object.entries/keys/for...in 无法枚举
    // 转成 JS Object 以支持属性枚举 (与 rhino NativeJavaMap.getIds() 行为一致)
    // 同时递归包装 value,处理嵌套的 Java 对象句柄
    if (v instanceof Map) {
        var obj = {};
        v.forEach(function(val, key) {
            obj[key] = __wrapJavaResult(val);
        });
        // 检测 __java_handle__ (quickjs-kt 可能把 {__java_handle__: handle} Map 转成 JS Map)
        // 转成 JS Object 后,需要再次检测并包装为 Proxy
        var handle = obj.__java_handle__;
        if (handle !== undefined && handle !== null) {
            return __wrapJavaObject(handle);
        }
        return obj;
    }
    if (typeof v === 'object') {
        var handle = v.__java_handle__;
        if (handle === undefined && typeof v.get === 'function') {
            handle = v.get('__java_handle__');
        }
        if (handle !== undefined && handle !== null) {
            return __wrapJavaObject(handle);
        }
    }
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
 * 把 Java 对象句柄包装为 JS Proxy。
 * JS 访问 prop 时(模拟 rhino LiveConnect 的成员查找):
 *   1. 尝试 field/getter(对应 rhino BeanProperty / NativeJavaField)
 *   2. 若 field 存在且同名 method 也存在 → 返回 FieldAndMessages 风格 callable
 *      (调用执行 method,Symbol.toPrimitive 返回 field 值,属性委托到 field 值)
 *   3. 若只有 field/getter → 返回值 (length 特判: List/Array 直接返回长度,不走 callable)
 *   4. 若 field 不存在 → 检查是否有同名 method:
 *      - 有 → 返回 method callable (如 list.add, map.size, str.substring)
 *      - 无 → 返回 undefined (与 rhino NativeJavaMap.getIds() 行为一致:
 *        Map 不含 key 时返回 NOT_FOUND,JS 侧 typeof 为 "undefined")
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
            // 先尝试 field/getter(对应 rhino 的 BeanProperty / NativeJavaField)
            var field = __wrapJavaResult(__getInstanceField(objHandle, prop, __dangerousApi__));
            if (field !== null && field !== undefined) {
                // 集合特判字段(length): List/Array 的 length 是 field 别名,不走 FieldAndMethods
                // 对齐 rhino NativeJavaList: length 直接返回值,不被同名方法 length() 覆盖为 callable
                // 注意: size 不特判,rhino 中 size 是 method,list.size() 调用返回长度
                if (prop === 'length') {
                    return field;
                }
                // field 存在,检查是否有同名 method (field+method 同名场景,如 StrResponse.body)
                // 与 rhino LiveConnect 行为一致: method 优先,返回 FieldAndMessages 风格 callable
                // (callable 调用执行 method,Symbol.toPrimitive 返回 field 值,属性委托到 field 值)
                if (__hasInstanceMethod(objHandle, prop, __dangerousApi__)) {
                    return __makeCallableValue(field, objHandle, prop);
                }
                // 纯 field/getter,返回值
                return field;
            }
            // field 不存在,检查是否有同名 method
            // - 有 method → 返回 method callable (如 list.add, map.size, str.substring)
            // - 无 method → 返回 undefined (如 map.missing,与 rhino NativeJavaMap 行为一致:
            //   missing key 不在 Map 中时返回 NOT_FOUND,JS 侧 typeof 为 "undefined")
            // 注意: 参数需经 __toPlainArgs 解引用,否则 quickjs-kt 无法识别 Proxy 参数
            if (__hasInstanceMethod(objHandle, prop, __dangerousApi__)) {
                return function() {
                    var args = Array.prototype.slice.call(arguments);
                    var plainArgs = __toPlainArgs(args);
                    return __wrapJavaResult(__callInstanceMethod(objHandle, prop, plainArgs, __dangerousApi__));
                };
            }
            return undefined;
        },
        set: function(target, prop, value) {
            if (typeof prop === 'string') {
                __setInstanceField(objHandle, prop, __toPlainArg(value), __dangerousApi__);
            }
            return true;
        }
        // 注意: 不添加 ownKeys/getOwnPropertyDescriptor trap。
        // 原因: native 层 getEvaluateResult 会通过这两个 trap 遍历 Proxy 属性,
        //       而 getOwnPropertyDescriptor 返回的 value 又是 Proxy,导致无限递归 → StackOverflowError。
        //       rhino 的 NativeJavaObject.getIds() 只在显式调用时枚举,不会在 native 转换时触发,
        //       quickjs-kt 的 native 层却会在 JS→Kotlin 转换时自动遍历,行为不同。
        //       Object.entries(javaProxy) 暂不支持,需在 JS 侧显式用 java.getKeys() 等 binding。
    });
}

/**
 * 解包 Java 句柄(用于 JavaAdapter 回调时传递参数)。
 */
function __unwrapJavaHandle(handle) {
    return __wrapJavaObject(handle);
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

// JS function 对象存储(用于 JavaAdapter 回调)
var __jsFnStore = {};
var __jsFnCounter = 0;

/**
 * 注册 JS function 对象,返回 Kotlin 侧 JsFunctionHandle 句柄。
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
