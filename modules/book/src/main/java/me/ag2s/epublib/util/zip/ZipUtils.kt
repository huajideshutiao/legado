package me.ag2s.epublib.util.zip

fun readLeShort(b: ByteArray, off: Int): Int {
    return (b[off].toInt() and 0xff) or (b[off + 1].toInt() and 0xff shl 8)
}

fun readLeInt(b: ByteArray, off: Int): Int {
    return (b[off].toInt() and 0xff or (b[off + 1].toInt() and 0xff shl 8)) or
        ((b[off + 2].toInt() and 0xff or (b[off + 3].toInt() and 0xff shl 8)) shl 16)
}
