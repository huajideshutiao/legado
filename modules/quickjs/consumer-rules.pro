# Add project specific ProGuard rules here.

############################
# QuickJS 引擎模块自身 (自封装 native QuickJS, 通过 JNI + JSClassExoticMethods trap 反射调用)
# JavaObjectBridge/JsBootstrap/QuickJsEngine 等通过 JS Proxy 反射链路调用,
# 私有方法(callHotTypeMethod/callMapMethod/collectMethods/getCollectionField/hasInstanceMethod)
# 不能被 R8 optimize 阶段内联或基于类型推断优化,否则 Map/List 的 instanceof 检查
# (is MutableMap/is Map)和 m.name == name 比较可能被破坏,导致 JS 端 body.get 失效。
############################
-keep class com.script.quickjs.** { *; }
-keepclassmembers class com.script.quickjs.** { *; }
