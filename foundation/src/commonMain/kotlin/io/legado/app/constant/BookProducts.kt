package io.legado.app.constant

/**
 * 书籍保存位置下承载**非书籍产物**的子目录名 (书籍本身——下载的与导出的成品——直接放保存位置根)。
 *
 * 产物与书籍同处一个用户选定目录, 但分属固定子目录: 备份 zip 落 [BOOK_BACKUP_DIR_NAME],
 * 日志 (运行日志、崩溃日志、日志包) 落 [LOG_DIR_NAME], 堆转储落 `heapDump`。
 *
 * 安卓端的保存位置是 SAF 树引用, 不能拼路径: 那里把本常量作为子目录**名**交给文档 API 在树内建出
 * 同名子目录; 桌面/iOS/鸿蒙是普通路径, 直接拼在保存位置之后。
 */
const val BOOK_BACKUP_DIR_NAME = "backup"

/**
 * 日志目录名。各端内部分散落点 (安卓外部缓存、桌面缓存、iOS/鸿蒙沙盒) 与书籍保存位置下的
 * 用户可见落点统一用本名; 运行日志与崩溃日志同目录, 靠文件名前缀区分
 * (运行日志 `appLog-`, 崩溃日志 [io.legado.app.help.crash.CRASH_LOG_FILE_PREFIX])。
 */
const val LOG_DIR_NAME = "log"
