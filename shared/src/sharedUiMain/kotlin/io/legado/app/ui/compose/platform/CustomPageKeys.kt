package io.legado.app.ui.compose.platform

import androidx.compose.ui.input.key.Key

/**
 * 自定义翻页按键 (对照原版 BaseReadActivity.isPrevKey/isNextKey + ReadBookActivity/
 * ReadMangaActivity 的 onKeyDown 消费, 2026-08 键盘迁移砍掉后恢复)。
 *
 * 偏好存储沿用原版格式: [io.legado.app.constant.PreferKey.prevKeys]/[nextKeys] =
 * Android keyCode 十进制逗号串 (原版由 Dialog setOnKeyListener 按物理键自动录入,
 * PageKeyDialog 已恢复该捕获)。
 *
 * 键值映射: Android 端 Compose Key 与 android.view.KeyEvent keyCode 恒等
 * (ui-android KeyEvent.android.kt: `Key(nativeKeyEvent.keyCode)`), 表外键码直接
 * Key(code) 兜底命中——蓝牙翻页器的厂商键码因此可用; 桌面/iOS/鸿蒙端 Compose Key
 * 取各自平台键值 (桌面为 AWT VK 码), 表内具名常量在两端各自取本平台正确值, 表外
 * 键码无平台对应键, 跳过。
 */
data class CustomPageKeys(val prev: List<Key>, val next: List<Key>) {

    fun isEmpty(): Boolean = prev.isEmpty() && next.isEmpty()

    /** 全部键位转为快捷键注册表; repeatPolicy 由调用方按原版语义选择 (小说 FILTER/漫画 TRIGGER) */
    fun shortcuts(repeatPolicy: KeyRepeatPolicy): List<AppShortcut> =
        prev.map { AppShortcut(it, preemptive = true, repeatPolicy = repeatPolicy) } +
            next.map { AppShortcut(it, preemptive = true, repeatPolicy = repeatPolicy) }

    fun isPrev(key: Key): Boolean = key in prev
}

/**
 * 解析 prevKeys/nextKeys 偏好串为键集合。
 * 对照原版 isPrevKey/isNextKey 的宽松语义: 逗号拆分 + 非数字段忽略;
 * keyCode <= 0 不参与匹配 (对照原版 KEYCODE_UNKNOWN 排除)。
 */
fun parseCustomPageKeys(prevKeys: String?, nextKeys: String?): CustomPageKeys {
    fun parse(raw: String?): List<Key> = raw.orEmpty()
        .split(",")
        .mapNotNull { it.trim().toIntOrNull() }
        .filter { it > 0 }
        .mapNotNull(::pageKeyCodeToKey)
    return CustomPageKeys(prev = parse(prevKeys), next = parse(nextKeys))
}

/** Android keyCode → Compose Key: 表内具名键双端命中; 表外 Android 恒等兜底, 其余平台无对应 */
fun pageKeyCodeToKey(code: Int): Key? = androidPageKeyTable[code] ?: identityKey(code)

/** Compose Key → Android keyCode (PageKeyDialog 物理键捕获录入用); 无对应返回 null */
fun keyToPageKeyCode(key: Key): Int? = keyToAndroidCodeTable[key] ?: identityCode(key)

/** 常用翻页相关键的 android keyCode ↔ common 具名 Key 映射 (方向/D Pad/翻页/空格/音量/回车/F 区/字母区) */
private val androidPageKeyTable: Map<Int, Key> = mapOf(
    19 to Key.DirectionUp,
    20 to Key.DirectionDown,
    21 to Key.DirectionLeft,
    22 to Key.DirectionRight,
    23 to Key.DirectionCenter,
    92 to Key.PageUp,
    93 to Key.PageDown,
    62 to Key.Spacebar,
    24 to Key.VolumeUp,
    25 to Key.VolumeDown,
    66 to Key.Enter,
    131 to Key.F1,
    132 to Key.F2,
    133 to Key.F3,
    134 to Key.F4,
    135 to Key.F5,
    136 to Key.F6,
    137 to Key.F7,
    138 to Key.F8,
    139 to Key.F9,
    140 to Key.F10,
    141 to Key.F11,
    142 to Key.F12,
    29 to Key.A,
    30 to Key.B,
    31 to Key.C,
    32 to Key.D,
    33 to Key.E,
    34 to Key.F,
    35 to Key.G,
    36 to Key.H,
    37 to Key.I,
    38 to Key.J,
    39 to Key.K,
    40 to Key.L,
    41 to Key.M,
    42 to Key.N,
    43 to Key.O,
    44 to Key.P,
    45 to Key.Q,
    46 to Key.R,
    47 to Key.S,
    48 to Key.T,
    49 to Key.U,
    50 to Key.V,
    51 to Key.W,
    52 to Key.X,
    53 to Key.Y,
    54 to Key.Z,
)

private val keyToAndroidCodeTable: Map<Key, Int> =
    androidPageKeyTable.entries.associate { (code, key) -> key to code }

/** Android 端恒等兜底 (表外厂商键码); 其余平台无恒等关系返回 null。 */
internal expect fun identityKey(code: Int): Key?

/** [identityKey] 的反向: Android 端 Key.keyCode 即 android keyCode; 其余平台返回 null。 */
internal expect fun identityCode(key: Key): Int?
