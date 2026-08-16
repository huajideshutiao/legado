# 桌面端 ProGuard 规则 (移植自 app/proguard-rules.pro 的 R8 配置, 按桌面端类路径裁剪)。
#
# 策略与 app 端一致:
# - 混淆关闭 (-dontobfuscate): 书源脚本经 QuickJS 桥按类名字符串反射加载 (Class.forName),
#   混淆改名会直接打断 JS 桥; 且混淆对体积收益很小 (真正的大头是死代码)。
# - 只做"死代码删除 + 轻度优化": 危险区 (Compose/serialization/Room/JNI/JS 桥/分派表)
#   全部 keep, 只裁掉确定无引用的类与成员。
# - 报表: build/proguard/{seeds,usage,mapping}.txt, 跑完先看 usage.txt 再决定接线打包。
#
# 注意 Kotlin 块注释可嵌套: 规则文件内不要出现裸 /* 序列 (用中文说明代替)。

############################
# 全局配置
############################
# 书源按类名反射加载, 混淆破坏 JS 桥; 且混淆省不了多少体积 (与 app 端同结论)
-dontobfuscate
# 只裁死代码, 不做字节码优化: ProGuard 优化器对部分 Kotlin/Compose 字节码会抛
# StackGeneralizationException (实测 error[1011]); 且优化有行为风险, 违背"危险区不动"。
# 体积大头在 shrink (死代码删除), 优化收益很小。
-dontoptimize
# 优化关闭后 allowaccessmodification 无意义 (它属于优化阶段), 不再声明
# -allowaccessmodification

# 保留行号、源文件信息以便排查崩溃堆栈
-keepattributes SourceFile,LineNumberTable
# 保留注解、内部类、签名（含 Kotlin 类型/泛型）、抛出声明
-keepattributes *Annotation*,InnerClasses,Signature,EnclosingMethod,Exceptions

############################
# Kotlin Intrinsics 空检查去除 (通用做法; 注: 需 -optimizationpasses 才生效,
# 当前 -dontoptimize 下为 no-op, 保留以便将来重开优化)
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
# 项目反射 keep (危险区, 随 shared/quickjs 下沉的规则照搬)
############################
-include ../shared/consumer-rules.pro
-include ../modules/quickjs/consumer-rules.pro

# AnalyzeRuleCore 下沉 commonMain 后无法用 androidx @Keep (无 common 变体), 按类名 keep (JS 反射调用其方法)
-keep,allowoptimization class io.legado.app.model.analyzeRule.AnalyzeRuleCore { *; }

# 书源 JS 面实现 (DesktopAnalyzeRule/DesktopAnalyzeUrl/DesktopBookSourceJsExt/HttpTTSJsExt 等,
# 均 implements JsExtensionsJvm; extends 在 ProGuard 中同样匹配接口实现类)
-keep class * extends io.legado.app.help.JsExtensionsJvm { *; }

# 数据实体 (Gson 反射 + Room + JS 访问; shared/consumer-rules.pro 已覆盖
# io.legado.app.data.entities.**, 此处兜底其它包的实体)
-keep class **.data.entities.** { *; }
-keep class io.legado.app.model.fileBook.ZipEntry { *; }
-keep class io.legado.app.model.fileBook.ZipImageCache { *; }

# 应用入口 (无其它类引用, 不 keep 会被当死代码删除 → 整个 UI 图连带裁掉)
-keep class io.legado.desktop.MainKt {
    public static void main(java.lang.String[]);
}

# 异常类型: 保留类名以便堆栈和反射查找
-keepnames class * extends java.lang.Throwable
-keepclassmembernames,allowobfuscation class * extends java.lang.Throwable { *; }

# JS 分派表: KSP 生成的 com.script.jsdispatch.generated.JsDispatchTables 由
# JsDispatchRegistry 按固定 FQN 字符串 Class.forName 引导注册, 不 keep 会被当死代码删除
# (删除后 JS 调用降级为纯反射, 功能仍可用但丢静态分派性能)
-keep class com.script.jsdispatch.generated.JsDispatchTables { *; }
-keep class com.script.jsdispatch.** { *; }

############################
# kotlinx.serialization (官方通用规则; 类名/序列化器不能被删, 含 $$serializer 生成类)
############################
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class **$$serializer { *; }
-keepclassmembers class ** {
    *** Companion;
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}

############################
# Room KMP: AppDatabase_Impl 等由 Room.getGeneratedImplementation 按
# "<database类名>_Impl" 字符串反射构造, 必须保留 (桌面端 room-runtime 是 JVM jar,
# 不带 Android AAR 的 consumer rules, 需手动补)
# 注: 本 fork 用 Room3 分支, 包名是 androidx.room3 (不是 androidx.room)
############################
-keep class * extends androidx.room3.RoomDatabase { *; }
-keep class **.*_Impl { *; }

############################
# JNI native 方法 (quickjs 桥 System.load 加载 native 库)
############################
-keepclasseswithmembernames class * { native <methods>; }

############################
# Hutool (书源 JS 直调 cn.hutool.crypto.SecureUtil/AES/SymmetricCrypto 等, 按名反射;
# 仅排除反射重灾区工具类, 与 app 端 R8 配置一致)
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
# OkHttp (保留给 js 调用)
############################
-keep class okhttp3.*{*;}
-keepclassmembers class okhttp3.** {    *** protocol(...);}
-dontwarn okhttp3.internal.**

############################
# Jsoup (书源 JS 解析 HTML, 反射调用)
############################
-keep class org.jsoup.** { *; }
-dontwarn org.jspecify.annotations.NullMarked

############################
# JNA (Structure/反射重)
############################
-keep class com.sun.jna.** { *; }
-dontwarn com.sun.jna.**

############################
# ServiceLoader 注册的依赖 (按 META-INF/services 名字符串实例化, 不 keep 会被删)
############################
# mediamp-mpv 播放器引擎 (ServiceLoader 发现)
-keep class org.openani.mediamp.** { *; }
# webp-imageio (javax.imageio.spi 注册)
-keep class com.github.gotson.webp.** { *; }
# slf4j-nop 的 NOPServiceProvider 经 ServiceLoader (META-INF/services) 按名实例化,
# 不 keep 会被删 → 启动期 SLF4J 警告 (功能仍回退 NOP, 但消除噪音)
-keep class org.slf4j.nop.** { *; }

############################
# 静默无关警告
############################
-dontwarn javax.annotation.**
-dontwarn org.codehaus.**
-dontwarn java.lang.invoke.StringConcatFactory

############################
# 可选/缺失的传递依赖与已知无害引用 (ProGuard 首次运行实测警告, 逐个排除)
############################
# commons-compress 的 pack200 与 truth 引用可选 asm (桌面端未引入)
-dontwarn org.objectweb.asm.**
# 测试库 truth 透传到运行时类路径 (引用 asm; 依赖清理另议)
-dontwarn com.google.common.truth.**
# commons-compress 可选压缩 codec
-dontwarn org.brotli.**
-dontwarn com.github.luben.zstd.**
# commons-compress 的 pack200 模块: 基于旧版 asm 编译, 字段解析必然告警;
# 应用不使用 Pack200 (只走 7z/tar/gz/bz2/xz), 该类会被 shrink 掉, 告警纯属噪音
-dontwarn org.apache.commons.compress.harmony.pack200.**
# commons-logging 与 log4j2 适配的方法签名差异 (仅日志兜底路径)
-dontwarn org.apache.commons.logging.**
# commons-logging 的 LogFactory 按类名字符串反射加载 LogFactoryImpl (pdfbox 等依赖),
# 不 keep 会被当死代码删除 → PDDocument 静态初始化抛 ClassNotFoundException
-keep class org.apache.commons.logging.** { *; }
# pdfbox 公钥加密 PDF 的可选 bcpkix (CMS/cert 未引入, 该功能不可用但类引用存在)
-dontwarn org.apache.pdfbox.**
# MethodHandle.invokeExact 多态签名: ProGuard 对 JDK 库类的已知限制 (jsvg/pdfbox IOUtils)
-dontwarn com.github.weisj.**
# kotlinx.coroutines.debug AgentPremain 引用 android 注解
-dontwarn android.annotation.**
