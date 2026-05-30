# Add project specific ProGuard rules here.
# 见 build.gradle 中 proguardFiles 配置（包含 proguard-android-optimize.txt 与 cronet-proguard-rules.pro）

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
-keepclassmembers,allowoptimization class * {
    @androidx.annotation.Keep <methods>;
    @androidx.annotation.Keep <fields>;
    @androidx.annotation.Keep <init>(...);
}

############################
# 业务：JS 引擎调用的 Java 类
############################
-keep class * extends io.legado.app.help.JsExtensions { *; }

############################
# 业务：数据实体（Gson 反射 + Room + JS 访问）
############################
-keep class **.data.entities.** { *; }

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

############################
# OkHttp / Okio（库自带 consumer rules 已覆盖大部分；仅保留必要兜底）
############################
-dontwarn okhttp3.internal.**
-dontwarn okio.**

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
# AndroidX activity：JsActivity 通过反射设置 OnBackPressedCallback
############################
-keepclassmembers class androidx.activity.OnBackPressedCallback {
    public boolean isEnabled();
    public void setEnabled(boolean);
}

############################
# 静默无关警告
############################
-dontwarn javax.annotation.**
-dontwarn org.codehaus.**
-dontwarn java.lang.invoke.StringConcatFactory
