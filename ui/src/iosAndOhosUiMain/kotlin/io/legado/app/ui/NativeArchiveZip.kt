package io.legado.app.ui

import io.legado.app.utils.File
import no.synth.kmpzip.okio.ZipOutputStream
import no.synth.kmpzip.zip.ZipConstants
import no.synth.kmpzip.zip.ZipEntry
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import okio.use

/** iOS/OHOS archive writer backed by kmp-zip; data is copied in bounded chunks. */
internal object NativeArchiveZip {
    data class Entry(val name: String, val file: File)

    fun write(target: File, entries: List<Entry>, checkCancelled: () -> Unit = {}) {
        require(entries.size <= 65535) { "ZIP 条目过多" }
        // Level-zero DEFLATE adds framing bytes, and ZIP headers grow with entry names.
        val estimatedSize = entries.sumOf {
            val size = it.file.length()
            require(size >= 0 && size < 0xffffffffL) { "文件超过 ZIP 格式限制" }
            size + size / 100 + it.name.encodeToByteArray().size * 2L + 256L
        }
        require(estimatedSize < 0xffffffffL) { "文件超过 ZIP 格式限制" }
        FileSystem.SYSTEM.sink(target.path.toPath()).buffer().use { sink ->
            ZipOutputStream(sink).use { zip ->
                // Deflate level 0 streams images without spending CPU on compression.
                zip.setLevel(0)
                val bytes = ByteArray(64 * 1024)
                entries.forEach { entry ->
                    checkCancelled()
                    require(!entry.name.startsWith('/') && !entry.name.split('/').contains(".."))
                    val zipEntry = ZipEntry(entry.name)
                    zipEntry.method = ZipConstants.DEFLATED
                    zip.putNextEntry(zipEntry)
                    FileSystem.SYSTEM.source(entry.file.path.toPath()).buffer().use { source ->
                        while (true) {
                            checkCancelled()
                            val count = source.read(bytes, 0, bytes.size)
                            if (count == -1) break
                            zip.write(bytes, 0, count)
                        }
                    }
                    zip.closeEntry()
                }
            }
        }
    }
}
