package io.legado.app.help.storage

import io.legado.app.exception.SecurityException
import io.legado.app.utils.File

/**
 * nativeMain 纯 Kotlin ZIP 编解码器 (无外部依赖)。
 *
 * 由 iosMain / ohosMain 共用 (nativeMain 中间源集下沉, 原 IosZipCodec/OhosZipCodec 合并)。
 *
 * # 背景
 * iOS/鸿蒙 Kotlin/Native 标准库不含 `java.util.zip`, iOS 默认绑定不含
 * `platform.Compression` / `platform.zlib` (需 cinterop 配置); 鸿蒙 (linuxArm64) 也不提供
 * `@ohos.zlib` 的 Kotlin/Native 绑定 (需 napi 桥接)。项目约束 (不改 build.gradle /
 * 不加 cinterop / 不引入新库) 排除了 minizip / SSZipArchive / kotlinx-io-compression 等方案。
 * 本文件用纯 Kotlin 实现 ZIP 编解码, 解除 iOS/鸿蒙端的备份/恢复阻塞。
 *
 * # 实现来源
 * 原 iosMain [IosZipCodec] (NSFileManager + NSData 文件 I/O) 与 ohosMain [OhosZipCodec]
 * (kotlin.io.File 文件 I/O) 逻辑完全一致 (字节级互通), 仅文件 I/O 层差异。
 * 下沉到 nativeMain 后统一用 [kotlin.io.File] (Kotlin/Native 标准库支持, iOS/鸿蒙可用)。
 *
 * # 实现范围
 * - CRC-32 (IEEE 802.3, 查表法)
 * - DEFLATE 解压 (RFC 1951 inflate, 支持 stored / fixed Huffman / 动态 Huffman 三种块)
 * - ZIP 文件格式读取 (解析 End of Central Directory + Central Directory + Local File Header,
 *   支持 STORED method=0 与 DEFLATE method=8)
 * - ZIP 文件格式写入 (DEFLATE 固定 Huffman, 不划算时回落 STORED;
 *   生成的 zip 可被 JVM/Android/桌面/标准工具解压)
 *
 * # 压缩策略
 * 写入用 [deflateFixed] (LZ77 + RFC 1951 固定 Huffman 表)。不做动态 Huffman: 建树/传树的收益
 * 相对固定表有限, 而实现与出错面大得多; 备份以 JSON 文本为主, 固定表已能压掉大半。
 * 每个 entry 压完立刻用本文件已验证的 [inflate] 回读比对, 不一致或压不小就退回 STORED —
 * 备份是用户数据, 宁可大也不能坏。
 *
 * # 解压策略
 * 同时支持 STORED 与 DEFLATE, 可解压 JVM 端 ZipUtils (默认 DEFLATE) 生成的备份 zip,
 * 实现跨端恢复。对齐 JVM 端 [io.legado.app.utils.compress.ZipUtils.unZipToPath] 的
 * 路径穿越防护 (entryName 含 "../" 抛 SecurityException)。
 *
 * # 局限 (TODO)
 * - 不支持 ZIP64 (文件 > 4GB / entry 数 > 65535); 备份场景不会触发
 * - 不支持加密 zip / zip 注释
 *
 * 参考:
 * - RFC 1951 (DEFLATE): https://datatracker.ietf.org/doc/html/rfc1951
 * - PKWARE APPNOTE 6.3.10 (ZIP 文件格式): https://pkware.cachefly.net/webdocs/casestudies/APPNOTE.TXT
 * - zlib puff.c (inflate 参考实现): https://github.com/madler/zlib/blob/master/contrib/puff/puff.c
 */
internal object NativeZipCodec {

    // ============================================================
    // 公共 API
    // ============================================================

    /**
     * 把多个文件/目录压成 zip (DEFLATE 固定 Huffman, 不划算时回落 STORED)。
     *
     * 行为对齐 jvmAndAndroidMain [ZipUtils.zipFiles]:
     * - 目录递归遍历, entry 名用相对路径 (相对于 srcPath 父目录, 保留 srcPath 本身名字)
     * - 文件 entry 名 = 文件名
     * - 路径分隔符恒为 "/" (zip 标准, POSIX 与 iOS/鸿蒙一致)
     *
     * @param srcPaths 源文件/目录路径列表
     * @param zipPath 目标 zip 文件路径 (父目录不存在则递归创建)
     * @return true 成功; false 失败 (源不存在 / IO 错误, 异常被吞掉)
     */
    fun zipFiles(srcPaths: List<String>, zipPath: String): Boolean {
        return try {
            // 确保 zip 父目录存在 (对齐 BackupFileOps.native writeText 行为)
            val parentDir = zipPath.substringBeforeLast('/')
            if (parentDir.isNotEmpty()) {
                File(parentDir).mkdirs()
            }

            val out = ByteBuilder()
            val centralDir = ByteBuilder()
            var centralDirCount = 0

            for (srcPath in srcPaths) {
                val entries = collectEntries(srcPath)
                for ((entryName, data) in entries) {
                    val crc = crc32(data)
                    val localHeaderOffset = out.size()
                    val nameBytes = entryName.encodeToByteArray()
                    // 压缩不划算或回读校验不过时退回 STORED (payload = 原数据)
                    val payload = compressOrStore(data)
                    val method = if (payload === data) METHOD_STORED else METHOD_DEFLATE

                    // 本地文件头 (Local File Header)
                    writeU32LE(out, LFH_SIGNATURE)
                    writeU16LE(out, VERSION_NEEDED)      // 解压所需版本 2.0
                    writeU16LE(out, 0)                   // 标志位 (无加密)
                    writeU16LE(out, method)              // 压缩方法
                    writeU16LE(out, 0)                   // 修改时间 (DOS, 0 = 00:00:00)
                    writeU16LE(out, 0)                   // 修改日期 (DOS, 0 = 1980-01-01)
                    writeU32LE(out, crc)                 // CRC-32 (恒为原始数据的 CRC)
                    writeU32LE(out, payload.size)        // 压缩后大小
                    writeU32LE(out, data.size)           // 未压缩大小
                    writeU16LE(out, nameBytes.size)      // 文件名长度
                    writeU16LE(out, 0)                   // 额外字段长度
                    out.add(nameBytes)
                    // 文件数据
                    out.add(payload)

                    // 中央目录头 (Central Directory Header)
                    writeU32LE(centralDir, CDH_SIGNATURE)
                    writeU16LE(centralDir, VERSION_MADE_BY)  // 制作版本 2.0
                    writeU16LE(centralDir, VERSION_NEEDED)   // 解压所需版本 2.0
                    writeU16LE(centralDir, 0)                // 标志位
                    writeU16LE(centralDir, method)           // 压缩方法
                    writeU16LE(centralDir, 0)                // 修改时间
                    writeU16LE(centralDir, 0)                // 修改日期
                    writeU32LE(centralDir, crc)              // CRC-32
                    writeU32LE(centralDir, payload.size)     // 压缩后大小
                    writeU32LE(centralDir, data.size)        // 未压缩大小
                    writeU16LE(centralDir, nameBytes.size)   // 文件名长度
                    writeU16LE(centralDir, 0)                // 额外字段长度
                    writeU16LE(centralDir, 0)                // 文件注释长度
                    writeU16LE(centralDir, 0)                // 磁盘号开始
                    writeU16LE(centralDir, 0)                // 内部文件属性
                    writeU32LE(centralDir, 0)                // 外部文件属性
                    writeU32LE(centralDir, localHeaderOffset)// 本地文件头偏移
                    centralDir.add(nameBytes)
                    centralDirCount++
                }
            }

            // 结束记录 (End of Central Directory Record)
            val centralDirOffset = out.size()
            val centralDirBytes = centralDir.toArray()
            out.add(centralDirBytes)
            writeU32LE(out, EOCD_SIGNATURE)
            writeU16LE(out, 0)                       // 磁盘号
            writeU16LE(out, 0)                       // 磁盘号开始
            writeU16LE(out, centralDirCount)         // 本磁盘上的中央目录条目数
            writeU16LE(out, centralDirCount)         // 总中央目录条目数
            writeU32LE(out, centralDirBytes.size)    // 中央目录大小
            writeU32LE(out, centralDirOffset)        // 中央目录偏移
            writeU16LE(out, 0)                       // 注释长度

            // 写入 zip 文件 (kotlin.io.File 无原子写, 与 BackupFileOps.native writeText 行为一致)
            File(zipPath).writeBytes(out.toArray())
            true
        } catch (e: Exception) {
            // 与 JVM 端 ZipUtils.zipFiles 行为对齐: 失败返回 false, 不抛
            // (BackupShared.kt 调用方检查返回值并 AppLog.put("zip 打包失败"))
            false
        }
    }

    /**
     * 把 zip 解压到目标目录。
     *
     * 行为对齐 jvmAndAndroidMain [ZipUtils.unZipToPath]:
     * - 解析 End of Central Directory → Central Directory → Local File Header
     * - 支持 STORED (method=0) 与 DEFLATE (method=8)
     * - 路径穿越防护: entryName 含 "../" 或绝对路径抛 [SecurityException]
     * - 目录 entry (以 "/" 结尾) 创建目录
     * - 文件 entry 父目录不存在则递归创建
     * - CRC-32 校验, 不匹配抛 [IllegalStateException]
     *
     * @param zipPath zip 文件路径
     * @param destDir 目标目录 (不存在则创建)
     * @throws SecurityException entry 路径穿越
     * @throws IllegalStateException zip 格式错误 / CRC 校验失败 / IO 错误
     */
    fun unZipToPath(zipPath: String, destDir: String) {
        val data = File(zipPath).readBytes()
        // 确保 destDir 存在
        File(destDir).mkdirs()
        forEachEntry(data) { entryName, entryData ->
            if (entryData == null) {
                // 目录 entry
                File((destDir + "/" + entryName).trimEnd('/')).mkdirs()
                return@forEachEntry
            }
            val destPath = destDir + "/" + entryName
            val parentDir = destPath.substringBeforeLast('/')
            if (parentDir.isNotEmpty()) {
                File(parentDir).mkdirs()
            }
            File(destPath).writeBytes(entryData)
        }
    }

    /**
     * 解压 zip 字节数组为 `entryName -> bytes` 映射 (目录 entry 跳过)。
     *
     * 等价 jvmAndAndroidMain 端 `ZipInputStream` 全量读入内存, 供 [unzipEpubEntries] 使用。
     */
    fun unzipToMap(zipData: ByteArray): Map<String, ByteArray> {
        val result = LinkedHashMap<String, ByteArray>()
        forEachEntry(zipData) { entryName, entryData ->
            if (entryData != null) result[entryName] = entryData
        }
        return result
    }

    /**
     * 遍历 zip 中央目录, 逐 entry 回调。
     * 目录 entry 回调 `data == null`, 文件 entry 回调解压并校验后的字节。
     */
    private inline fun forEachEntry(data: ByteArray, onEntry: (String, ByteArray?) -> Unit) {
        if (data.size < EOCD_MIN_SIZE) {
            throw IllegalStateException("NativeZipCodec.unZipToPath: zip too small (${data.size} bytes)")
        }

        // 1. 找到 End of Central Directory Record
        val eocdOffset = findEocd(data)
        val cdCount = readU16LE(data, eocdOffset + 10)
        val cdOffset = readU32LE(data, eocdOffset + 16)

        // 2. 遍历中央目录
        var pos = cdOffset
        for (i in 0 until cdCount) {
            if (readU32LE(data, pos) != CDH_SIGNATURE) {
                throw IllegalStateException("NativeZipCodec: invalid central directory header at $pos")
            }
            val method = readU16LE(data, pos + 10)
            val crc = readU32LE(data, pos + 16)
            val compressedSize = readU32LE(data, pos + 20)
            val uncompressedSize = readU32LE(data, pos + 24)
            val nameLen = readU16LE(data, pos + 28)
            val extraLen = readU16LE(data, pos + 30)
            val commentLen = readU16LE(data, pos + 32)
            val localHeaderOffset = readU32LE(data, pos + 42)
            val entryName = data.decodeToString(pos + 46, pos + 46 + nameLen)
            pos += 46 + nameLen + extraLen + commentLen

            // 路径穿越防护 (对齐 JVM 端 canonicalPath.startsWith 检查)
            if (entryName.contains("../") || entryName.startsWith("/")) {
                throw SecurityException("NativeZipCodec: zip entry path unsafe: $entryName")
            }

            // 目录 entry (以 "/" 结尾)
            if (entryName.endsWith("/")) {
                onEntry(entryName, null)
                continue
            }

            // 3. 读本地文件头获取实际数据偏移 (本地头里的 name/extra 长度可能与中央目录不同)
            if (readU32LE(data, localHeaderOffset) != LFH_SIGNATURE) {
                throw IllegalStateException("NativeZipCodec: invalid local file header at $localHeaderOffset")
            }
            val localNameLen = readU16LE(data, localHeaderOffset + 26)
            val localExtraLen = readU16LE(data, localHeaderOffset + 28)
            val dataOffset = localHeaderOffset + 30 + localNameLen + localExtraLen

            // 4. 解压 entry 数据
            val entryData = when (method) {
                METHOD_STORED -> data.copyOfRange(dataOffset, dataOffset + compressedSize)
                METHOD_DEFLATE -> inflate(data, dataOffset, compressedSize)
                else -> throw IllegalStateException(
                    "NativeZipCodec: unsupported compression method $method for $entryName"
                )
            }

            // 5. 校验大小 + CRC (对齐 JVM 端 ZipEntry 行为, 确保数据完整)
            if (entryData.size != uncompressedSize) {
                throw IllegalStateException(
                    "NativeZipCodec: size mismatch for $entryName (expected $uncompressedSize, got ${entryData.size})"
                )
            }
            if (crc32(entryData) != crc) {
                throw IllegalStateException("NativeZipCodec: CRC mismatch for $entryName")
            }

            onEntry(entryName, entryData)
        }
    }

    // ============================================================
    // DEFLATE 解压 (RFC 1951 inflate)
    // ============================================================

    /**
     * 解压 raw DEFLATE 流 (RFC 1951, 无 zlib/gzip 包裹)。
     *
     * 等价 JVM 端 `java.util.zip.Inflater(nowrap=true)`。
     * 用于解压 zip entry 中 method=8 的压缩数据。
     *
     * 同时供 [io.legado.app.model.fileBook.inflateRaw] (iOS/鸿蒙 actual) 复用,
     * 解除 iOS/鸿蒙端 RemoteZipCore / 远程 zip 链路的 DEFLATE 解压 stub 限制。
     *
     * @param input 包含 DEFLATE 流的字节数组
     * @param offset DEFLATE 流起始偏移
     * @param length DEFLATE 流长度
     * @return 解压后的字节数组
     */
    internal fun inflate(input: ByteArray, offset: Int, length: Int): ByteArray {
        val reader = BitReader(input, offset, offset + length)
        val out = ByteBuilder(if (length < 256) 256 else length * 3)

        while (true) {
            val bfinal = reader.readBit()
            val btype = reader.readBits(2)
            when (btype) {
                0 -> inflateStored(reader, out)
                1 -> inflateBlock(reader, out, FIXED_LIT_TREE, FIXED_DIST_TREE)
                2 -> {
                    val (litTree, distTree) = readDynamicTrees(reader)
                    inflateBlock(reader, out, litTree, distTree)
                }
                else -> throw IllegalStateException("NativeZipCodec: invalid DEFLATE block type $btype")
            }
            if (bfinal == 1) break
        }
        return out.toArray()
    }

    /** Stored block (BTYPE=00): 跳过 partial byte, 读 LEN/NLEN, 拷贝 LEN 字节。 */
    private fun inflateStored(reader: BitReader, out: ByteBuilder) {
        reader.alignToByte()
        val len = reader.readByte() or (reader.readByte() shl 8)
        val nlen = reader.readByte() or (reader.readByte() shl 8)
        // NLEN = LEN 的反码 (16-bit), 用于校验
        if ((len.inv() and 0xFFFF) != nlen) {
            throw IllegalStateException("NativeZipCodec: stored block length check failed (len=$len, nlen=$nlen)")
        }
        repeat(len) { out.add(reader.readByte()) }
    }

    /** 读取动态 Huffman 树 (BTYPE=10)。 */
    private fun readDynamicTrees(reader: BitReader): Pair<HuffmanTree, HuffmanTree> {
        val hlit = reader.readBits(5) + 257   // 字面/长度码数 (257-286)
        val hdist = reader.readBits(5) + 1    // 距离码数 (1-32)
        val hclen = reader.readBits(4) + 4    // 码长码数 (4-19)

        // 1. 读取 19 个 code length 码长 (按 clOrder 顺序)
        val clOrder = intArrayOf(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)
        val clLengths = IntArray(19)
        for (i in 0 until hclen) {
            clLengths[clOrder[i]] = reader.readBits(3)
        }
        val clTree = HuffmanTree(clLengths)

        // 2. 用 clTree 解码 hlit+hdist 个字面/距离码长
        val lengths = IntArray(hlit + hdist)
        var i = 0
        while (i < lengths.size) {
            val sym = clTree.decode(reader)
            if (sym < 16) {
                lengths[i++] = sym
            } else if (sym == 16) {
                // 复制前一个码长, 2 bits 额外 (repeat 3-6 次)
                val repeat = reader.readBits(2) + 3
                if (i == 0) throw IllegalStateException("NativeZipCodec: code length 16 at index 0")
                val prev = lengths[i - 1]
                repeat(repeat) {
                    if (i >= lengths.size) throw IllegalStateException("NativeZipCodec: code length repeat overflow")
                    lengths[i++] = prev
                }
            } else if (sym == 17) {
                // 重复 0, 3 bits 额外 (repeat 3-10 次)
                val repeat = reader.readBits(3) + 3
                repeat(repeat) {
                    if (i >= lengths.size) throw IllegalStateException("NativeZipCodec: code length 17 overflow")
                    lengths[i++] = 0
                }
            } else if (sym == 18) {
                // 重复 0, 7 bits 额外 (repeat 11-138 次)
                val repeat = reader.readBits(7) + 11
                repeat(repeat) {
                    if (i >= lengths.size) throw IllegalStateException("NativeZipCodec: code length 18 overflow")
                    lengths[i++] = 0
                }
            } else {
                throw IllegalStateException("NativeZipCodec: invalid code length symbol $sym")
            }
        }

        // 3. 前 hlit 个是字面/长度码长, 后 hdist 个是距离码长
        val litTree = HuffmanTree(lengths.copyOfRange(0, hlit))
        val distTree = HuffmanTree(lengths.copyOfRange(hlit, hlit + hdist))
        return litTree to distTree
    }

    /** 解压一个 Huffman 块 (BTYPE=01/10): 解码字面/长度+距离, LZ77 还原。 */
    private fun inflateBlock(reader: BitReader, out: ByteBuilder, litTree: HuffmanTree, distTree: HuffmanTree) {
        while (true) {
            val sym = litTree.decode(reader)
            if (sym < 256) {
                // 字面字节
                out.add(sym)
            } else if (sym == 256) {
                // 块结束标记
                break
            } else {
                // 长度+距离匹配 (LZ77)
                val lenIdx = sym - 257
                if (lenIdx >= LENGTH_BASE.size) {
                    throw IllegalStateException("NativeZipCodec: invalid length symbol $sym")
                }
                val length = LENGTH_BASE[lenIdx] + reader.readBits(LENGTH_EXTRA[lenIdx])
                val distSym = distTree.decode(reader)
                if (distSym >= DIST_BASE.size) {
                    throw IllegalStateException("NativeZipCodec: invalid distance symbol $distSym")
                }
                val distance = DIST_BASE[distSym] + reader.readBits(DIST_EXTRA[distSym])
                val start = out.size() - distance
                if (start < 0) {
                    throw IllegalStateException("NativeZipCodec: distance too far back (distance=$distance, out=${out.size()})")
                }
                // 逐字节复制 (允许重叠: distance < length 时, 后续字节依赖本次复制结果)
                for (j in 0 until length) {
                    out.add(out.get(start + j))
                }
            }
        }
    }

    // ============================================================
    // DEFLATE 压缩 (RFC 1951, LZ77 + 固定 Huffman)
    // ============================================================

    /**
     * 压缩 entry 数据; 压不小或回读校验失败时原样返回 [data] (调用方据引用相等判定 STORED)。
     *
     * 回读校验用本文件已被 JVM 产物验证过的 [inflate], 是把未经真机验证的编码器投产的前提。
     */
    private fun compressOrStore(data: ByteArray): ByteArray {
        if (data.size < MIN_DEFLATE_SIZE) return data
        val deflated = runCatching { deflateFixed(data) }.getOrNull() ?: return data
        if (deflated.size >= data.size) return data
        val verified = runCatching {
            inflate(deflated, 0, deflated.size).contentEquals(data)
        }.getOrDefault(false)
        return if (verified) deflated else data
    }

    /**
     * 生成 raw DEFLATE 流 (RFC 1951, 无 zlib/gzip 包裹), 单个 final 固定 Huffman 块。
     *
     * 等价 JVM 端 `java.util.zip.Deflater(level, nowrap=true)` 的输出格式 (码表固定, 压缩率略低)。
     * 匹配查找用 3 字节哈希链, 窗口 32KB, 链长上限 [MAX_CHAIN] 控制最坏耗时。
     */
    private fun deflateFixed(data: ByteArray): ByteArray {
        val out = ByteBuilder(data.size / 2 + 64)
        val writer = BitWriter(out)
        writer.writeBits(1, 1)  // BFINAL = 1 (单块到底)
        writer.writeBits(1, 2)  // BTYPE = 01 (固定 Huffman)

        val head = IntArray(HASH_SIZE) { -1 }
        val prev = IntArray(if (data.isEmpty()) 1 else data.size) { -1 }
        var pos = 0
        while (pos < data.size) {
            var bestLen = 0
            var bestDist = 0
            if (pos + MIN_MATCH <= data.size) {
                val maxLen = minOf(MAX_MATCH, data.size - pos)
                val h = hash3(data, pos)
                var candidate = head[h]
                var chain = 0
                while (candidate >= 0 && chain < MAX_CHAIN) {
                    val dist = pos - candidate
                    // 链上后续候选只会更远, 越窗即可整体停
                    if (dist <= 0 || dist > MAX_DISTANCE) break
                    var len = 0
                    while (len < maxLen && data[candidate + len] == data[pos + len]) len++
                    if (len > bestLen) {
                        bestLen = len
                        bestDist = dist
                        if (len >= maxLen) break
                    }
                    candidate = prev[candidate]
                    chain++
                }
                prev[pos] = head[h]
                head[h] = pos
            }
            if (bestLen >= MIN_MATCH) {
                writeLengthCode(writer, bestLen)
                writeDistanceCode(writer, bestDist)
                // 匹配跨过的位置也要入链, 否则后续匹配率骤降
                for (k in 1 until bestLen) {
                    val p = pos + k
                    if (p + MIN_MATCH > data.size) break
                    val h2 = hash3(data, p)
                    prev[p] = head[h2]
                    head[h2] = p
                }
                pos += bestLen
            } else {
                writeLiteralCode(writer, data[pos].toInt() and 0xFF)
                pos++
            }
        }
        writeLiteralCode(writer, 256)  // 块结束标记
        writer.flush()
        return out.toArray()
    }

    /** 3 字节滚动哈希 (对齐 zlib 的 hash chain 思路)。 */
    private fun hash3(data: ByteArray, pos: Int): Int {
        val a = data[pos].toInt() and 0xFF
        val b = data[pos + 1].toInt() and 0xFF
        val c = data[pos + 2].toInt() and 0xFF
        return ((a shl 10) xor (b shl 5) xor c) and (HASH_SIZE - 1)
    }

    /** 写字面/长度符号的固定 Huffman 码 (RFC 1951 §3.2.6 码表)。 */
    private fun writeLiteralCode(writer: BitWriter, sym: Int) {
        when {
            sym <= 143 -> writer.writeCode(0x30 + sym, 8)
            sym <= 255 -> writer.writeCode(0x190 + sym - 144, 9)
            sym <= 279 -> writer.writeCode(sym - 256, 7)
            else -> writer.writeCode(0xC0 + sym - 280, 8)
        }
    }

    /** 写长度码 (符号 257-285) + 额外位。 */
    private fun writeLengthCode(writer: BitWriter, length: Int) {
        var idx = LENGTH_BASE.size - 1
        while (idx > 0 && LENGTH_BASE[idx] > length) idx--
        writeLiteralCode(writer, 257 + idx)
        val extra = LENGTH_EXTRA[idx]
        if (extra > 0) writer.writeBits(length - LENGTH_BASE[idx], extra)
    }

    /** 写距离码 (固定表恒 5 位) + 额外位。 */
    private fun writeDistanceCode(writer: BitWriter, distance: Int) {
        var idx = DIST_BASE.size - 1
        while (idx > 0 && DIST_BASE[idx] > distance) idx--
        writer.writeCode(idx, 5)
        val extra = DIST_EXTRA[idx]
        if (extra > 0) writer.writeBits(distance - DIST_BASE[idx], extra)
    }

    // ============================================================
    // ZIP 文件格式辅助
    // ============================================================

    /**
     * 递归收集 srcPath 下所有文件 (对齐 JVM 端 ZipUtils.zipFile 递归行为)。
     *
     * 目录/文件判断用 [File.isDirectory] (Kotlin/Native kotlin.io.File 标准属性);
     * 子项遍历用 [File.listFiles]。
     *
     * @return List<(entryName, data)>, entryName 相对于 srcPath 父目录
     */
    private fun collectEntries(srcPath: String): List<Pair<String, ByteArray>> {
        val result = mutableListOf<Pair<String, ByteArray>>()
        val src = File(srcPath)
        if (!src.exists()) return result

        val baseName = srcPath.substringAfterLast('/')
        if (!src.isDirectory) {
            // 单文件: entry 名 = 文件名
            result.add(baseName to src.readBytes())
            return result
        }
        // 目录: 递归遍历子项
        collectDirEntries(src, baseName, result)
        return result
    }

    /** 递归收集目录下所有文件 (entry 名 = prefix + "/" + 相对子路径)。 */
    private fun collectDirEntries(dir: File, prefix: String, result: MutableList<Pair<String, ByteArray>>) {
        val children = dir.listFiles() ?: return
        for (child in children) {
            val entryName = "$prefix/${child.name}"
            if (child.isDirectory) {
                collectDirEntries(child, entryName, result)
            } else {
                result.add(entryName to child.readBytes())
            }
        }
    }

    /** 从后往前查找 End of Central Directory Record 签名 (注释最大 65535 字节)。 */
    private fun findEocd(data: ByteArray): Int {
        val minPos = maxOf(0, data.size - EOCD_MAX_SIZE)
        for (i in data.size - EOCD_MIN_SIZE downTo minPos) {
            if (readU32LE(data, i) == EOCD_SIGNATURE) return i
        }
        throw IllegalStateException("NativeZipCodec: End of Central Directory not found")
    }

    // ============================================================
    // 工具函数
    // ============================================================

    /** CRC-32 (IEEE 802.3), 查表法, 与 java.util.zip.CRC32 / zlib crc32 一致。 */
    private fun crc32(data: ByteArray, offset: Int = 0, length: Int = data.size): Int {
        var crc = 0xFFFFFFFF.toInt()
        for (i in offset until offset + length) {
            crc = CRC_TABLE[(crc xor data[i].toInt()) and 0xFF] xor (crc ushr 8)
        }
        return crc xor 0xFFFFFFFF.toInt()
    }

    private fun writeU16LE(buf: ByteBuilder, value: Int) {
        buf.add(value and 0xFF)
        buf.add((value ushr 8) and 0xFF)
    }

    private fun writeU32LE(buf: ByteBuilder, value: Int) {
        buf.add(value and 0xFF)
        buf.add((value ushr 8) and 0xFF)
        buf.add((value ushr 16) and 0xFF)
        buf.add((value ushr 24) and 0xFF)
    }

    private fun readU16LE(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    private fun readU32LE(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)

    // ============================================================
    // 常量
    // ============================================================

    // ZIP 签名
    private const val LFH_SIGNATURE = 0x04034b50   // PK\x03\x04 本地文件头
    private const val CDH_SIGNATURE = 0x02014b50   // PK\x01\x02 中央目录头
    private const val EOCD_SIGNATURE = 0x06054b50  // PK\x05\x06 结束记录

    // 压缩方法
    private const val METHOD_STORED = 0
    private const val METHOD_DEFLATE = 8

    // 版本
    private const val VERSION_MADE_BY = 20          // 2.0 (支持 STORED/DEFLATE)
    private const val VERSION_NEEDED = 20

    // EOCD 大小 (最小 22 字节, 含注释最大 22 + 65535)
    private const val EOCD_MIN_SIZE = 22
    private const val EOCD_MAX_SIZE = 22 + 65535

    // DEFLATE 编码参数
    /** 小于此字节数不压 (头部开销大于收益)。 */
    private const val MIN_DEFLATE_SIZE = 64
    private const val MIN_MATCH = 3
    private const val MAX_MATCH = 258
    private const val MAX_DISTANCE = 32768
    private const val HASH_SIZE = 1 shl 15
    /** 单个位置最多回溯的候选数, 压缩率与耗时的折中。 */
    private const val MAX_CHAIN = 128

    /** CRC-32 查表 (多项式 0xEDB88320, 反射形式)。 */
    private val CRC_TABLE = IntArray(256) { i ->
        var c = i
        repeat(8) {
            c = if (c and 1 != 0) 0xEDB88320.toInt() xor (c ushr 1) else c ushr 1
        }
        c
    }

    // DEFLATE 长度码 (sym 257-285): 基础长度 + 额外位数
    private val LENGTH_BASE = intArrayOf(
        3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31,
        35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258
    )
    private val LENGTH_EXTRA = intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2,
        3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0
    )

    // DEFLATE 距离码 (sym 0-29): 基础距离 + 额外位数
    private val DIST_BASE = intArrayOf(
        1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193,
        257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577
    )
    private val DIST_EXTRA = intArrayOf(
        0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6,
        7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13
    )

    /** 固定 Huffman 字面/长度树 (RFC 1951 §3.2.6): 0-143→8bits, 144-255→9bits, 256-279→7bits, 280-287→8bits。 */
    private val FIXED_LIT_TREE: HuffmanTree by lazy {
        val lengths = IntArray(288)
        for (i in 0..143) lengths[i] = 8
        for (i in 144..255) lengths[i] = 9
        for (i in 256..279) lengths[i] = 7
        for (i in 280..287) lengths[i] = 8
        HuffmanTree(lengths)
    }

    /** 固定 Huffman 距离树: 所有 30 个符号 5 bits。 */
    private val FIXED_DIST_TREE: HuffmanTree by lazy {
        HuffmanTree(IntArray(30) { 5 })
    }
}

// ============================================================
// 内部辅助类 (文件级 private, 不暴露到 NativeZipCodec 外)
// ============================================================

/** 可动态扩展的字节缓冲区 (等价 JVM ByteArrayOutputStream, Kotlin/Native 无标准库实现)。 */
private class ByteBuilder(initialCapacity: Int = 256) {
    private var buf = ByteArray(initialCapacity)
    private var sz = 0

    fun add(b: Int) = add(b.toByte())

    fun add(b: Byte) {
        ensureCapacity(sz + 1)
        buf[sz++] = b
    }

    fun add(arr: ByteArray, offset: Int = 0, length: Int = arr.size) {
        ensureCapacity(sz + length)
        arr.copyInto(buf, sz, offset, offset + length)
        sz += length
    }

    fun size(): Int = sz

    fun get(index: Int): Byte = buf[index]

    fun toArray(): ByteArray = buf.copyOf(sz)

    private fun ensureCapacity(minCapacity: Int) {
        if (minCapacity > buf.size) {
            var newCap = buf.size
            while (newCap < minCapacity) newCap = newCap * 3 / 2 + 1
            buf = buf.copyOf(newCap)
        }
    }
}

/**
 * DEFLATE 位流写入器 (与 [BitReader] 镜像)。
 *
 * RFC 1951 §3.1.1 的两种位序: 数值型字段 (块头/额外位) LSB first, Huffman 码 MSB first。
 */
private class BitWriter(private val out: ByteBuilder) {
    private var bitBuf = 0
    private var bitCount = 0

    /** 写 count 位数值 (LSB first)。 */
    fun writeBits(value: Int, count: Int) {
        for (i in 0 until count) {
            bitBuf = bitBuf or (((value ushr i) and 1) shl bitCount)
            bitCount++
            if (bitCount == 8) {
                out.add(bitBuf)
                bitBuf = 0
                bitCount = 0
            }
        }
    }

    /** 写 Huffman 码字 (MSB first)。 */
    fun writeCode(code: Int, length: Int) {
        for (i in length - 1 downTo 0) {
            writeBits((code ushr i) and 1, 1)
        }
    }

    /** 补零对齐到字节边界 (块写完必须调用)。 */
    fun flush() {
        if (bitCount > 0) {
            out.add(bitBuf)
            bitBuf = 0
            bitCount = 0
        }
    }
}

/** DEFLATE 位流读取器 (LSB first, RFC 1951 §3.1.1)。 */
private class BitReader(private val data: ByteArray, private val start: Int, private val end: Int) {    private var bytePos = start
    private var bitPos = 0  // 当前字节中已消费的位数 (0..7)

    fun readBit(): Int {
        if (bytePos >= end) throw IllegalStateException("NativeZipCodec: bit stream exhausted")
        val bit = (data[bytePos].toInt() ushr bitPos) and 1
        bitPos++
        if (bitPos == 8) {
            bitPos = 0
            bytePos++
        }
        return bit
    }

    /** 读 count 位 (LSB first, 低位先读)。 */
    fun readBits(count: Int): Int {
        var result = 0
        for (i in 0 until count) {
            result = result or (readBit() shl i)
        }
        return result
    }

    /** 对齐到字节边界 (丢弃当前字节剩余位)。 */
    fun alignToByte() {
        if (bitPos != 0) {
            bitPos = 0
            bytePos++
        }
    }

    /** 读一个完整字节 (必须已对齐)。 */
    fun readByte(): Int {
        if (bitPos != 0) throw IllegalStateException("NativeZipCodec: not byte-aligned")
        if (bytePos >= end) throw IllegalStateException("NativeZipCodec: byte stream exhausted")
        return data[bytePos++].toInt() and 0xFF
    }
}

/**
 * Canonical Huffman 树 (RFC 1951 §3.2.2), 用于 DEFLATE 解码。
 *
 * 构建: 给定每个符号的码长, 按 canonical 顺序分配码字 (同码长内按符号值升序)。
 * 解码: 逐位读入, 累加匹配 (参考 zlib puff.c decode 实现)。
 */
private class HuffmanTree(lengths: IntArray) {
    private val counts = IntArray(MAX_BITS + 1)   // counts[len] = 码长为 len 的符号数
    private val symbols = IntArray(lengths.size)  // 按码长排序的符号

    init {
        for (len in lengths) {
            if (len > MAX_BITS) {
                throw IllegalStateException("NativeZipCodec: Huffman code length $len > $MAX_BITS")
            }
            counts[len]++
        }
        counts[0] = 0  // 码长 0 表示符号未使用

        // 计算每个码长的符号起始偏移
        val offsets = IntArray(MAX_BITS + 2)
        for (i in 1..MAX_BITS) {
            offsets[i + 1] = offsets[i] + counts[i]
        }

        // 填充 symbols (同码长内按符号值升序)
        for (sym in lengths.indices) {
            val len = lengths[sym]
            if (len > 0) {
                symbols[offsets[len]++] = sym
            }
        }
    }

    /** 逐位解码一个符号。 */
    fun decode(reader: BitReader): Int {
        var code = 0
        var first = 0
        var index = 0
        for (len in 1..MAX_BITS) {
            code = (code shl 1) or reader.readBit()
            val count = counts[len]
            if (code - first < count) {
                return symbols[index + (code - first)]
            }
            index += count
            first = (first + count) shl 1
        }
        throw IllegalStateException("NativeZipCodec: invalid Huffman code")
    }

    companion object {
        private const val MAX_BITS = 15
    }
}
