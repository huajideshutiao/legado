package com.fleeksoft.charset

actual object Platform {
    actual val current: PlatformType
        get() = PlatformType.LINUX
}
