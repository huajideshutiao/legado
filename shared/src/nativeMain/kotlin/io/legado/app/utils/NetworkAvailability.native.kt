package io.legado.app.utils

/** iOS/鸿蒙无等价的同步连通性查询, 恒可用; 失败由调用方异常处理兜底。 */
actual fun isNetworkAvailable(): Boolean = true
