@file:Suppress("unused")

package androidx.compose.ui.tooling.preview

/**
 * 鸿蒙 (ohos) Preview 注解兼容层。
 *
 * sharedUiMain 的 @Preview 函数引用 androidx.compose.ui.tooling.preview.Preview
 * (CMP 1.10+ 的 commonMain 声明, 依赖 org.jetbrains.compose.ui:ui-tooling-preview)。
 * CPF compose-ohos fork 无该库的 ohosArm64 变体 (shared/build.gradle.kts 已在 ohos
 * target 排除该依赖), 此处提供同包同名注解保证 ohos 编译通过。
 *
 * 放 ohosCompatMain (commonMain 在 enableOhosTarget 时的附加源码目录, 见
 * shared/build.gradle.kts commonMain.srcDir): ohos 构建时 commonMain 包含本注解,
 * sharedUiMain 的引用可解析; 非 ohos 构建 (含 iOS) 不包含, 使用真实
 * ui-tooling-preview 库注解, 无同 FQN 冲突。
 *
 * 注解仅元数据用途, ohos 无 IDE/desktop 预览工具链, 运行期无行为。
 * 参数面与 CMP 版 Preview 常用参数对齐, 覆盖 sharedUiMain 现有使用点。
 */
@Target(
    AnnotationTarget.FUNCTION,
    AnnotationTarget.CLASS,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FILE,
)
@Retention(AnnotationRetention.BINARY)
annotation class Preview(
    val name: String = "",
    val group: String = "",
    val showBackground: Boolean = false,
    val backgroundColor: Long = 0,
    val showDecoration: Boolean = false,
    val showSystemUi: Boolean = false,
    val widthDp: Int = -1,
    val heightDp: Int = -1,
    val locale: String = "",
    val fontScale: Float = 1f,
    val darkTheme: Boolean = false,
    val uiMode: Int = 0,
    val showLightStatusBar: Boolean = false,
    val showLightNavigationBar: Boolean = false,
    val device: String = "",
)
