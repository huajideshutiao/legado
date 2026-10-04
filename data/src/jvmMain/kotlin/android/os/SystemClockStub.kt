// android.os.SystemClock JVM stub: elapsedRealtime 用系统单调时钟等价实现, 供限流等路径真实计时。
package android.os

object SystemClock {

    @JvmStatic
    fun elapsedRealtime(): Long = System.nanoTime() / 1_000_000
}
