package androidx.room3

import kotlin.reflect.KClass

// 3.0.1 已将 TypeConverters/TypeConverter 改名为 ColumnTypeConverters/ColumnTypeConverter,
// 但 CPF 鸿蒙 room3 fork (3.0.0-alpha01-0.3.0) 的 runtime/klib 只认识 alpha01 时代的旧名
// (fork 未发布新版, eazytec 仓库 room3-runtime-ohosarm64 最新就是 3.0.0-alpha01-0.3.0)。
//
// 处理方式: Book/AppDatabase 上的 converter 注册与转换函数同时使用新旧两套注解
// (双注册, 见 Book.kt 顶部注释); 3.0.1 KSP 处理器只看 ColumnTypeConverters/ColumnTypeConverter,
// alpha01 KSP 处理器只看 TypeConverters/TypeConverter, 各取所需。
//
// 本文件只在非鸿蒙构建编译 (commonMain 的 srcDir 按 enableOhosTarget 切换), 让 3.0.1 构建
// 也能解析旧名注解 (3.0.1 处理器对未知 FQN 一律忽略, 不会参与注册, 也不会报错)。
// 鸿蒙构建不编译本文件: fork klib 自带同名注解, 重复声明会冲突。
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class TypeConverters(vararg val value: KClass<*> = [])

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class TypeConverter
