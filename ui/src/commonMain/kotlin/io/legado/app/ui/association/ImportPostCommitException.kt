package io.legado.app.ui.association

/**
 * 数据已经提交、但提交后的缓存维护失败。
 *
 * 调用方须把本次导入视为已写入: 不重新插入、不复位确认终态; 只把缓存失败如实反馈给用户。
 */
class ImportPostCommitException(message: String) : Exception(message)
