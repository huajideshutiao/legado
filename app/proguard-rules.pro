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

# JS 反射 keep 规则随 :shared 删除已按类归属迁至 core/data/foundation 各自
# consumer-rules.pro（按类归属拆到 core/data/foundation 三者）；AGP KMP
# library 的 consumer rules 发布行为未验证，沿用旧策略由最终 app 显式 include，
# 保证 R8 混淆/shrink 下书源 JS 按名反射调用的类保活。
-include ../core/consumer-rules.pro
-include ../data/consumer-rules.pro
-include ../foundation/consumer-rules.pro
-include ../modules/quickjs/consumer-rules.pro
-keepclassmembers,allowoptimization class * {
    @androidx.annotation.Keep <methods>;
    @androidx.annotation.Keep <fields>;
    @androidx.annotation.Keep <init>(...);
}

############################
# 业务：JS 引擎调用的 Java 类
############################
# 书源 JS 面实现类 (BookSource/HttpTTS/AnalyzeRuleCore/AnalyzeUrlCore/RssJsExtensionsJvm 等):
# JS 桥经 JavaObjectBridge 按方法名反射调用 (collectMethods 走 clazz.methods, 含接口默认实现
# 桥方法), 不 keep 时未被 Kotlin 直接调用的成员会被当死代码删除。
# 注: JS 侧拿到的是运行时实例 (evalJS 绑 this), 类本身由 Kotlin 构造点可达, 故只需保成员。
-keep class * implements io.legado.app.help.JsExtensionsCommon { *; }

############################
# 业务：数据实体（Gson 反射 + Room + JS 访问）
############################
-keep class **.data.entities.** { *; }
-keep class io.legado.app.model.fileBook.ZipEntry { *; }
-keep class io.legado.app.model.fileBook.ZipImageCache { *; }

############################
# 业务：TVBox spider jar 运行时类路径供给
############################
# spider jar 经 DexClassLoader 双亲委派按类名解析宿主类, 宿主源码对 zxing 零引用,
# 不 keep 会被 R8 整库裁光, jar 内 Init 二维码任务在 release 包必然 NoClassDefFoundError。
# zxing core 为纯 Java 直调库 (无反射/无服务发现); 实测 custom_spider.jar 常量池只引用下列
# 编码面入口, 其余格式 Writer 与其共用件 (BitArray/reedsolomon/MatrixUtil 等) 由 R8 按可达性自动保留。
# 解析面 (Reader/decoder/Binarizer/LuminanceSource/client.result/multi/maxicode) 宿主与 jar 都不用,
# 不再 keep, 交由 R8 裁除。
-keep class com.google.zxing.MultiFormatWriter { *; }
-keep class com.google.zxing.qrcode.QRCodeWriter { *; }
-keep class com.google.zxing.EncodeHintType { *; }
-keep class com.google.zxing.BarcodeFormat { *; }
-keep class com.google.zxing.common.BitMatrix { *; }
# com.github.catvod.* 是 spider jar 的类路径契约面 (壳类/工具/网络, 签名须与 FongMi catvod 一致),
# 宿主源码只引用少数接线点, 其余成员仅被 jar 直调; 纯 Java 直调面, jar 用法覆盖面不可预判,
# 不 keep 会被 R8 整库裁光 (release 包 init/取数期 NoClassDefFoundError)。
-keep class com.github.catvod.** { *; }
# com.google.gson.** 同为 spider jar 运行时契约 (FongMi catvod 以 api 暴露 gson,
# 生态 jar 可直调), 混淆改名后 jar 按 FQCN 链接即断。
-keep class com.google.gson.** { *; }

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
# OkHttp（保留给 js 调用）
############################
-keep class okhttp3.*{*;}
-keepclassmembers class okhttp3.** {    *** protocol(...);}
-dontwarn okhttp3.internal.**

############################
# Markwon
############################
-dontwarn org.commonmark.ext.gfm.**

############################
# Jsoup / RE2J
############################
-keep class org.jsoup.** { *; }

############################
# 扩展动态加载面：Tachiyomi/Aniyomi 扩展按 compileOnly 引用这些库，运行时委派宿主 PathClassLoader；
# 宿主自身不可达的类/成员会被 R8 裁剪，扩展调用即 NoSuchMethodError。
# 对齐官方宿主 aniyomi app/proguard-rules.pro 的 "Keep common dependencies used in extensions" 段。
############################
-keep,allowoptimization class kotlin.** { public protected *; }
-keep,allowoptimization class kotlinx.coroutines.** { public protected *; }
-keep,allowoptimization class kotlinx.serialization.** { public protected *; }
-keep,allowoptimization class okio.** { public protected *; }
-keep,allowoptimization class uy.kohesive.injekt.** { public protected *; }
# injekt 的 fullType<T>() 在调用点内联出 FullTypeReference 的匿名子类, 构造经
# javaClass.genericSuperclass 反射取泛型实参; R8 仅在 keep 规则匹配到的类上保留 Signature 属性,
# 子类被优化/合并即丢失泛型父类信息, 反射退化为裸 Class, release 包启动即
# IllegalArgumentException: TypeReference constructed without actual type information
# (规则与 keiyoushi 扩展源 common/proguard-rules.pro 逐字一致)。
-keep class * extends uy.kohesive.injekt.api.FullTypeReference
-dontwarn org.jspecify.annotations.NullMarked

############################
# Gson TypeToken (object : TypeToken<...>() {} 同款反射契约, 规则取自 Gson 官方 README)
############################
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken
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
# 扩展/jar 运行时引用的宿主 API 面：扩展 APK 按 eu.kanade.tachiyomi.*
# 以 compileOnly 编译，这些类型仅在扩展 dex 中被引用，R8 静态不可见，
# 缺 keep 即被收缩/合并出 dex，扩展加载即 ClassNotFound（规则对齐 Mihon 官方 proguard-rules.pro）
############################
-keep,allowoptimization class eu.kanade.** { *; }
-keep,allowoptimization class androidx.preference.** { public protected *; }
# okhttp3 子包: 上方只有单星 okhttp3.*, 不含 okhttp3.internal 等子包
-keep,allowoptimization class okhttp3.** { public protected *; }

############################
# @file:JvmName 合成类跨模块引用加固（顶级 Kotlin 函数宿主类）
############################
-keep class io.legado.app.utils.GsonStreamExtensions { *; }
-keep class io.legado.app.utils.EventBusObserveExtensions { *; }
-keep class io.legado.app.utils.ConvertExtensionsAndroid { *; }
-keep class io.legado.app.help.IntentDataAndroid { *; }
-keep class io.legado.app.help.storage.BackupAESAndroid { *; }
