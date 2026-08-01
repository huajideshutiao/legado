# Add project specific ProGuard rules here.

############################
# 全局配置
############################
# 无需混淆，方便编写书源且体积优化不明显
-dontobfuscate
-optimizationpasses 5
-allowaccessmodification

# 保留行号、源文件信息以便排查崩溃堆栈
-keepattributes SourceFile,LineNumberTable
# 保留注解、内部类、签名（含 Kotlin 类型/泛型）、抛出声明
-keepattributes *Annotation*,InnerClasses,Signature,EnclosingMethod,Exceptions

############################
# 通用：去除日志
############################
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int i(...);
    public static int w(...);
    public static int d(...);
    public static int e(...);
}

############################
# Kotlin Intrinsics 空检查去除
############################
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    static void checkParameterIsNotNull(java.lang.Object, java.lang.String);
    static void checkNotNullParameter(java.lang.Object, java.lang.String);
    static void checkExpressionValueIsNotNull(java.lang.Object, java.lang.String);
    static void checkNotNullExpressionValue(java.lang.Object, java.lang.String);
    static void checkReturnedValueIsNotNull(java.lang.Object, java.lang.String);
    static void checkFieldIsNotNull(java.lang.Object, java.lang.String);
    static void throwUninitializedPropertyAccessException(java.lang.String);
}

############################
# @Keep 通用规则（项目内大量类已使用 @Keep，简化重复 -keep）
############################
-keep,allowoptimization @androidx.annotation.Keep class * { *; }
# AnalyzeRuleCore 下沉 commonMain 后无法用 androidx @Keep (无 common 变体), 按类名 keep (JS 反射调用其方法)
-keep,allowoptimization class io.legado.app.model.analyzeRule.AnalyzeRuleCore { *; }

# Android-KMP library 的 consumer keep rules 发布在 AGP 8.13 尚不可用，
# 先由最终 app 统一承载 shared/quickjs 的反射与 JNI 保留规则。
-include ../shared/consumer-rules.pro
-include ../modules/quickjs/consumer-rules.pro
-keepclassmembers,allowoptimization class * {
    @androidx.annotation.Keep <methods>;
    @androidx.annotation.Keep <fields>;
    @androidx.annotation.Keep <init>(...);
}

############################
# 业务：JS 引擎调用的 Java 类
############################
-keep class * extends io.legado.app.help.JsExtensionsJvm { *; }

############################
# 业务：数据实体（Gson 反射 + Room + JS 访问）
############################
-keep class **.data.entities.** { *; }
-keep class io.legado.app.model.fileBook.ZipEntry { *; }
-keep class io.legado.app.model.fileBook.ZipImageCache { *; }

############################
# 异常类型：保留类名以便堆栈和反射查找
############################
-keepnames class * extends java.lang.Throwable
-keepclassmembernames,allowobfuscation class * extends java.lang.Throwable { *; }

############################
# Hutool（仅保留实际使用的工具类，反射类排除）
############################
-keep class
    !cn.hutool.core.util.RuntimeUtil,
    !cn.hutool.core.util.ClassLoaderUtil,
    !cn.hutool.core.util.ReflectUtil,
    !cn.hutool.core.util.SerializeUtil,
    !cn.hutool.core.util.ClassUtil,
    cn.hutool.core.codec.**,
    cn.hutool.core.util.** { *; }
-keep class cn.hutool.crypto.** { *; }
-dontwarn cn.hutool.**
# rhino compileOnly 不进产物,适配层残留引用仅警告豁免
-dontwarn org.mozilla.javascript.**
-dontwarn com.script.*

############################
# OkHttp（保留给 js 调用）
############################
-keep class okhttp3.*{*;}
-keepclassmembers class okhttp3.** {    *** protocol(...);}
-dontwarn okhttp3.internal.**

############################
# JsonPath
############################
-keep class com.jayway.jsonpath.** { *; }
-dontwarn com.jayway.jsonpath.**

############################
# Markwon
############################
-dontwarn org.commonmark.ext.gfm.**

############################
# Jsoup / RE2J
############################
-keep class org.jsoup.** { *; }
-dontwarn org.jspecify.annotations.NullMarked
-keep class com.google.re2j.** { *; }
-dontwarn com.google.re2j.**

############################
# AndroidX appcompat 私有 API（ChangeBookSourceDialog / MenuExtensions 反射使用）
############################
-keepclassmembers class androidx.appcompat.widget.Toolbar {
    *** mNavButtonView;
}
-keepnames class androidx.appcompat.view.menu.SubMenuBuilder
-keep class androidx.appcompat.view.menu.MenuBuilder {
    *** setOptionalIconsVisible(...);
    *** getNonActionItems();
}

############################
# AndroidX documentfile：FileDocExtensions 通过 Class.forName 反射构造
############################
-keep class androidx.documentfile.provider.TreeDocumentFile {
    <init>(...);
}

############################
# 静默无关警告
############################
-dontwarn javax.annotation.**
-dontwarn org.codehaus.**
-dontwarn java.lang.invoke.StringConcatFactory

############################
# @file:JvmName 合成类跨模块引用加固（顶级 Kotlin 函数宿主类）
############################
-keep class io.legado.app.utils.GsonStreamExtensions { *; }
-keep class io.legado.app.utils.EventBusObserveExtensions { *; }
-keep class io.legado.app.utils.ConvertExtensionsAndroid { *; }
-keep class io.legado.app.help.IntentDataAndroid { *; }
-keep class io.legado.app.help.storage.BackupAESAndroid { *; }
