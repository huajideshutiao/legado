// android.R JVM 空壳: 插件偏好输入框动态校验合同用到 id.button1 (确认键); 值与真实平台
// 一致, 保证宿主查找与插件常量内联后的比对命中。仅存在于 :data 的 jvm 变体 (data/src/jvmMain),
// :app (android 变体) 编译与打包不接触本目录。
package android

class R private constructor() {

    class id private constructor() {

        companion object {
            /** 对齐 android.R.id.button1。 */
            const val button1: Int = 0x01020019
        }
    }
}
