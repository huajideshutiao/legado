package io.legado.app.model.analyzeRule

/**
 * TTF 字体解析器，供书源字体反混淆使用。纯字节逻辑，四端共用。
 * @see <a href="https://learn.microsoft.com/zh-cn/typography/opentype/spec/otff">OpenType 文档</a>
 */
class QueryTTF(buffer: ByteArray) {

    /** 文件头 */
    private class Header {
        var sfntVersion = 0L
        var numTables = 0
        var searchRange = 0
        var entrySelector = 0
        var rangeShift = 0
    }

    /** 数据表目录 */
    private class Directory {
        var tableTag = ""
        var checkSum = 0
        var offset = 0
        var length = 0
    }

    private class NameLayout {
        var format = 0
        var count = 0
        var stringOffset = 0
        val records = ArrayList<NameRecord>()
    }

    private class NameRecord {
        var platformID = 0
        var encodingID = 0
        var languageID = 0
        var nameID = 0
        var length = 0
        var offset = 0
    }

    /** Font Header Table，仅 indexToLocFormat 后续使用，其余读取以推进位置 */
    private class HeadLayout {
        var majorVersion = 0
        var minorVersion = 0
        var fontRevision = 0
        var checkSumAdjustment = 0
        var magicNumber = 0
        var flags = 0
        var unitsPerEm = 0
        var created = 0L
        var modified = 0L
        var xMin = 0
        var yMin = 0
        var xMax = 0
        var yMax = 0
        var macStyle = 0
        var lowestRecPPEM = 0
        var fontDirectionHint = 0
        var indexToLocFormat = 0 // 0=短偏移 Offset16, 1=长偏移 Offset32
        var glyphDataFormat = 0
    }

    /** Maximum Profile，仅 numGlyphs 后续使用 */
    private class MaxpLayout {
        var version = 0
        var numGlyphs = 0
        var maxPoints = 0
        var maxContours = 0
        var maxCompositePoints = 0
        var maxCompositeContours = 0
        var maxZones = 0
        var maxTwilightPoints = 0
        var maxStorage = 0
        var maxFunctionDefs = 0
        var maxInstructionDefs = 0
        var maxStackElements = 0
        var maxSizeOfInstructions = 0
        var maxComponentElements = 0
        var maxComponentDepth = 0
    }

    /** 字符到字形索引映射表 */
    private class CmapLayout {
        var version = 0
        var numTables = 0
        val records = ArrayList<CmapRecord>()
        val tables = HashMap<Int, CmapFormat>()
    }

    private class CmapRecord {
        var platformID = 0
        var encodingID = 0
        var offset = 0
    }

    /** cmap 子表，字段按 format 分别使用（不支持的 format2 字段已省略） */
    private class CmapFormat {
        var format = 0
        var length = 0
        var language = 0
        var segCountX2 = 0
        var searchRange = 0
        var entrySelector = 0
        var rangeShift = 0
        var endCode = IntArray(0)
        var reservedPad = 0
        var startCode = IntArray(0)
        var idDelta = IntArray(0)
        var idRangeOffsets = IntArray(0)
        var firstCode = 0
        var entryCount = 0
        var glyphIdArray = IntArray(0)
    }

    /** 字形轮廓数据表 */
    private class GlyfLayout {
        var numberOfContours = 0 // 非负=简单字形轮廓数, 负=复合字形
        var xMin = 0
        var yMin = 0
        var xMax = 0
        var yMax = 0
        var glyphSimple: GlyphTableBySimple? = null
        var glyphComponent: ArrayList<GlyphTableComponent>? = null
    }

    private class GlyphTableBySimple {
        var endPtsOfContours = IntArray(0)
        var instructionLength = 0
        var instructions = IntArray(0)
        var flags = IntArray(0)
        var xCoordinates = IntArray(0)
        var yCoordinates = IntArray(0)
    }

    private class GlyphTableComponent {
        var flags = 0
        var glyphIndex = 0
        var argument1 = 0
        var argument2 = 0
        var xScale = 0f
        var scale01 = 0f
        var scale10 = 0f
        var yScale = 0f
    }

    /** 大端字节读取器（替代 java.nio.ByteBuffer）；方法名保持与原 Java 版一致 */
    @Suppress("FunctionName")
    private class BufferReader(private val buffer: ByteArray, index: Int) {
        private var pos = index

        fun position(index: Int) {
            pos = index
        }

        fun position(): Int = pos

        fun ReadUInt64(): Long {
            var v = 0L
            repeat(8) { v = (v shl 8) or (buffer[pos + it].toLong() and 0xFF) }
            pos += 8
            return v
        }

        fun ReadUInt32(): Int {
            val v = ((buffer[pos].toInt() and 0xFF) shl 24) or
                ((buffer[pos + 1].toInt() and 0xFF) shl 16) or
                ((buffer[pos + 2].toInt() and 0xFF) shl 8) or
                (buffer[pos + 3].toInt() and 0xFF)
            pos += 4
            return v
        }

        fun ReadInt32(): Int = ReadUInt32()

        fun ReadUInt16(): Int {
            val v = ((buffer[pos].toInt() and 0xFF) shl 8) or (buffer[pos + 1].toInt() and 0xFF)
            pos += 2
            return v
        }

        fun ReadInt16(): Int = ReadUInt16().toShort().toInt()

        fun ReadUInt8(): Int = buffer[pos++].toInt() and 0xFF

        fun ReadInt8(): Int = buffer[pos++].toInt()

        fun ReadByteArray(len: Int): ByteArray {
            val result = buffer.copyOfRange(pos, pos + len)
            pos += len
            return result
        }

        fun ReadUInt8Array(len: Int): IntArray = IntArray(len) { ReadUInt8() }

        fun ReadInt16Array(len: Int): IntArray = IntArray(len) { ReadInt16() }

        fun ReadUInt16Array(len: Int): IntArray = IntArray(len) { ReadUInt16() }

        fun ReadInt32Array(len: Int): IntArray = IntArray(len) { ReadInt32() }
    }

    private val fileHeader = Header()
    private val directorys = HashMap<String, Directory>()
    private val name = NameLayout()
    private val head = HeadLayout()
    private val maxp = MaxpLayout()
    private val Cmap = CmapLayout()
    private val pps = arrayOf(
        intArrayOf(3, 10), intArrayOf(0, 4), intArrayOf(3, 1),
        intArrayOf(1, 0), intArrayOf(0, 3), intArrayOf(0, 1)
    )

    /** glyfId 到 glyphData 的索引，loca.size = maxp.numGlyphs + 1 */
    private lateinit var loca: IntArray

    /** 字形轮廓表数组 */
    private lateinit var glyfArray: Array<GlyfLayout?>

    val unicodeToGlyph = HashMap<Int, String?>()
    val glyphToUnicode = HashMap<String, Int>()
    val unicodeToGlyphId = HashMap<Int, Int>()

    init {
        val fontReader = BufferReader(buffer, 0)
        // 读文件头
        fileHeader.sfntVersion = fontReader.ReadUInt32().toLong()
        fileHeader.numTables = fontReader.ReadUInt16()
        fileHeader.searchRange = fontReader.ReadUInt16()
        fileHeader.entrySelector = fontReader.ReadUInt16()
        fileHeader.rangeShift = fontReader.ReadUInt16()
        // 读目录
        for (i in 0 until fileHeader.numTables) {
            val d = Directory()
            d.tableTag = fontReader.ReadByteArray(4).decodeAscii()
            d.checkSum = fontReader.ReadUInt32()
            d.offset = fontReader.ReadUInt32()
            d.length = fontReader.ReadUInt32()
            directorys[d.tableTag] = d
        }

        readNameTable(buffer)   // 字体信息(版权/名称/作者)
        readHeadTable(buffer)   // 取 indexToLocFormat
        readCmapTable(buffer)   // Unicode->轮廓索引 对照表
        readLocaTable(buffer)   // 轮廓数据偏移地址表
        readMaxpTable(buffer)   // 取 numGlyphs 字体轮廓数量
        readGlyfTable(buffer)   // 字体轮廓数据表

        // 建立 Unicode & Glyph 映射表
        val glyfArrayLength = glyfArray.size
        for ((key, value) in unicodeToGlyphId) {
            if (value >= glyfArrayLength) continue
            val glyfString = getGlyfById(value)
            unicodeToGlyph[key] = glyfString
            if (glyfString == null) continue // null 不能用作 hashmap 的 key
            glyphToUnicode[glyfString] = key
        }
    }

    private fun readNameTable(buffer: ByteArray) {
        val dataTable = directorys["name"]!!
        val reader = BufferReader(buffer, dataTable.offset)
        name.format = reader.ReadUInt16()
        name.count = reader.ReadUInt16()
        name.stringOffset = reader.ReadUInt16()
        for (i in 0 until name.count) {
            val record = NameRecord()
            record.platformID = reader.ReadUInt16()
            record.encodingID = reader.ReadUInt16()
            record.languageID = reader.ReadUInt16()
            record.nameID = reader.ReadUInt16()
            record.length = reader.ReadUInt16()
            record.offset = reader.ReadUInt16()
            name.records.add(record)
        }
    }

    private fun readHeadTable(buffer: ByteArray) {
        val dataTable = directorys["head"]!!
        val reader = BufferReader(buffer, dataTable.offset)
        head.majorVersion = reader.ReadUInt16()
        head.minorVersion = reader.ReadUInt16()
        head.fontRevision = reader.ReadUInt32()
        head.checkSumAdjustment = reader.ReadUInt32()
        head.magicNumber = reader.ReadUInt32()
        head.flags = reader.ReadUInt16()
        head.unitsPerEm = reader.ReadUInt16()
        head.created = reader.ReadUInt64()
        head.modified = reader.ReadUInt64()
        head.xMin = reader.ReadInt16()
        head.yMin = reader.ReadInt16()
        head.xMax = reader.ReadInt16()
        head.yMax = reader.ReadInt16()
        head.macStyle = reader.ReadUInt16()
        head.lowestRecPPEM = reader.ReadUInt16()
        head.fontDirectionHint = reader.ReadInt16()
        head.indexToLocFormat = reader.ReadInt16()
        head.glyphDataFormat = reader.ReadInt16()
    }

    private fun readLocaTable(buffer: ByteArray) {
        val dataTable = directorys["loca"]!!
        val reader = BufferReader(buffer, dataTable.offset)
        if (head.indexToLocFormat == 0) {
            loca = reader.ReadUInt16Array(dataTable.length / 2)
            // loca 表数据为 Uint16 时需翻倍
            for (i in loca.indices) loca[i] *= 2
        } else {
            loca = reader.ReadInt32Array(dataTable.length / 4)
        }
    }

    private fun readCmapTable(buffer: ByteArray) {
        val dataTable = directorys["cmap"]!!
        val reader = BufferReader(buffer, dataTable.offset)
        Cmap.version = reader.ReadUInt16()
        Cmap.numTables = reader.ReadUInt16()
        for (i in 0 until Cmap.numTables) {
            val record = CmapRecord()
            record.platformID = reader.ReadUInt16()
            record.encodingID = reader.ReadUInt16()
            record.offset = reader.ReadUInt32()
            Cmap.records.add(record)
        }

        for (formatTable in Cmap.records) {
            val fmtOffset = formatTable.offset
            if (Cmap.tables.containsKey(fmtOffset)) continue
            reader.position(dataTable.offset + fmtOffset)

            val f = CmapFormat()
            f.format = reader.ReadUInt16()
            f.length = reader.ReadUInt16()
            f.language = reader.ReadUInt16()
            when (f.format) {
                0 -> {
                    f.glyphIdArray = reader.ReadUInt8Array(f.length - 6)
                    // 记录 unicode->glyphId 映射表
                    val unicodeExclusive = f.glyphIdArray.size
                    var unicodeInclusive = 0
                    while (unicodeInclusive < unicodeExclusive) {
                        if (f.glyphIdArray[unicodeInclusive] != 0) { // 排除轮廓索引为0的Unicode
                            unicodeToGlyphId[unicodeInclusive] = f.glyphIdArray[unicodeInclusive]
                        }
                        unicodeInclusive++
                    }
                }

                4 -> {
                    f.segCountX2 = reader.ReadUInt16()
                    val segCount = f.segCountX2 / 2
                    f.searchRange = reader.ReadUInt16()
                    f.entrySelector = reader.ReadUInt16()
                    f.rangeShift = reader.ReadUInt16()
                    f.endCode = reader.ReadUInt16Array(segCount)
                    f.reservedPad = reader.ReadUInt16()
                    f.startCode = reader.ReadUInt16Array(segCount)
                    f.idDelta = reader.ReadInt16Array(segCount)
                    f.idRangeOffsets = reader.ReadUInt16Array(segCount)
                    // 含字形索引的数组，长度取决于映射复杂度与字符数量
                    val glyphIdArrayLength = (f.length - 16 - (segCount * 8)) / 2
                    f.glyphIdArray = reader.ReadUInt16Array(glyphIdArrayLength)

                    // 记录 unicode->glyphId 映射表
                    for (segmentIndex in 0 until segCount) {
                        val unicodeInclusive = f.startCode[segmentIndex]
                        val unicodeExclusive = f.endCode[segmentIndex]
                        val idDelta = f.idDelta[segmentIndex]
                        val idRangeOffset = f.idRangeOffsets[segmentIndex]
                        for (unicode in unicodeInclusive..unicodeExclusive) {
                            var glyphId = 0
                            if (idRangeOffset == 0) {
                                glyphId = (unicode + idDelta) and 0xFFFF
                            } else {
                                val gIndex =
                                    (idRangeOffset / 2) + unicode - unicodeInclusive + segmentIndex - segCount
                                if (gIndex < glyphIdArrayLength) {
                                    glyphId = f.glyphIdArray[gIndex] + idDelta
                                }
                            }
                            if (glyphId == 0) continue // 排除轮廓索引为0的Unicode
                            unicodeToGlyphId[unicode] = glyphId
                        }
                    }
                }

                6 -> {
                    f.firstCode = reader.ReadUInt16()
                    f.entryCount = reader.ReadUInt16()
                    // 范围内字符代码的字形索引值数组
                    f.glyphIdArray = reader.ReadUInt16Array(f.entryCount)

                    // 记录 unicode->glyphId 映射表
                    var unicodeIndex = f.firstCode
                    val unicodeCount = f.entryCount
                    for (gIndex in 0 until unicodeCount) {
                        unicodeToGlyphId[unicodeIndex] = f.glyphIdArray[gIndex]
                        unicodeIndex++
                    }
                }

                else -> {}
            }
            Cmap.tables[fmtOffset] = f
        }
    }

    private fun readMaxpTable(buffer: ByteArray) {
        val dataTable = directorys["maxp"]!!
        val reader = BufferReader(buffer, dataTable.offset)
        maxp.version = reader.ReadUInt32()
        maxp.numGlyphs = reader.ReadUInt16()
        maxp.maxPoints = reader.ReadUInt16()
        maxp.maxContours = reader.ReadUInt16()
        maxp.maxCompositePoints = reader.ReadUInt16()
        maxp.maxCompositeContours = reader.ReadUInt16()
        maxp.maxZones = reader.ReadUInt16()
        maxp.maxTwilightPoints = reader.ReadUInt16()
        maxp.maxStorage = reader.ReadUInt16()
        maxp.maxFunctionDefs = reader.ReadUInt16()
        maxp.maxInstructionDefs = reader.ReadUInt16()
        maxp.maxStackElements = reader.ReadUInt16()
        maxp.maxSizeOfInstructions = reader.ReadUInt16()
        maxp.maxComponentElements = reader.ReadUInt16()
        maxp.maxComponentDepth = reader.ReadUInt16()
    }

    private fun readGlyfTable(buffer: ByteArray) {
        val dataTable = directorys["glyf"]!!
        val glyfCount = maxp.numGlyphs
        glyfArray = arrayOfNulls(glyfCount) // 创建字形容器

        val reader = BufferReader(buffer, 0)
        for (index in 0 until glyfCount) {
            if (loca[index] == loca[index + 1]) continue // loca 与下一个相同表示字形不存在
            val offset = dataTable.offset + loca[index]
            // 读 GlyphHeaders
            val glyph = GlyfLayout()
            reader.position(offset)
            glyph.numberOfContours = reader.ReadInt16()
            if (glyph.numberOfContours > maxp.maxContours) continue // 轮廓数超上限，字形无效
            glyph.xMin = reader.ReadInt16()
            glyph.yMin = reader.ReadInt16()
            glyph.xMax = reader.ReadInt16()
            glyph.yMax = reader.ReadInt16()

            // 轮廓数为0时无需解析轮廓数据
            if (glyph.numberOfContours == 0) continue
            // 读 Glyph 轮廓数据
            if (glyph.numberOfContours > 0) {
                // 简单轮廓
                val simple = GlyphTableBySimple()
                glyph.glyphSimple = simple
                simple.endPtsOfContours = reader.ReadUInt16Array(glyph.numberOfContours)
                simple.instructionLength = reader.ReadUInt16()
                simple.instructions = reader.ReadUInt8Array(simple.instructionLength)
                val flagLength = simple.endPtsOfContours[simple.endPtsOfContours.size - 1] + 1
                // 获取轮廓点描述标志
                simple.flags = IntArray(flagLength)
                var n = 0
                while (n < flagLength) {
                    val glyphSimpleFlag = reader.ReadUInt8()
                    simple.flags[n] = glyphSimpleFlag
                    if ((glyphSimpleFlag and 0x08) == 0x08) {
                        var m = reader.ReadUInt8()
                        while (m > 0) {
                            n++
                            simple.flags[n] = glyphSimpleFlag
                            m--
                        }
                    }
                    n++
                }
                // 获取轮廓点描述x轴相对值
                simple.xCoordinates = IntArray(flagLength)
                for (i in 0 until flagLength) {
                    when (simple.flags[i] and 0x12) {
                        0x02 -> simple.xCoordinates[i] = -reader.ReadUInt8()
                        0x12 -> simple.xCoordinates[i] = reader.ReadUInt8()
                        0x10 -> simple.xCoordinates[i] = 0 // 重复上一次数据，相对变化量为0
                        0x00 -> simple.xCoordinates[i] = reader.ReadInt16()
                    }
                }
                // 获取轮廓点描述y轴相对值
                simple.yCoordinates = IntArray(flagLength)
                for (i in 0 until flagLength) {
                    when (simple.flags[i] and 0x24) {
                        0x04 -> simple.yCoordinates[i] = -reader.ReadUInt8()
                        0x24 -> simple.yCoordinates[i] = reader.ReadUInt8()
                        0x20 -> simple.yCoordinates[i] = 0 // 重复上一次数据，相对变化量为0
                        0x00 -> simple.yCoordinates[i] = reader.ReadInt16()
                    }
                }
            } else {
                // 复合轮廓
                val component = ArrayList<GlyphTableComponent>()
                glyph.glyphComponent = component
                while (true) {
                    val c = GlyphTableComponent()
                    c.flags = reader.ReadUInt16()
                    c.glyphIndex = reader.ReadUInt16()
                    when (c.flags and 0b11) {
                        0b00 -> {
                            c.argument1 = reader.ReadUInt8()
                            c.argument2 = reader.ReadUInt8()
                        }

                        0b10 -> {
                            c.argument1 = reader.ReadInt8()
                            c.argument2 = reader.ReadInt8()
                        }

                        0b01 -> {
                            c.argument1 = reader.ReadUInt16()
                            c.argument2 = reader.ReadUInt16()
                        }

                        0b11 -> {
                            c.argument1 = reader.ReadInt16()
                            c.argument2 = reader.ReadInt16()
                        }
                    }
                    when (c.flags and 0b11001000) {
                        0b00001000 -> {
                            // 单一比例
                            c.xScale = reader.ReadUInt16().toFloat() / 16384.0f
                            c.yScale = c.xScale
                        }

                        0b01000000 -> {
                            // X 和 Y 独立比例
                            c.xScale = reader.ReadUInt16().toFloat() / 16384.0f
                            c.yScale = reader.ReadUInt16().toFloat() / 16384.0f
                        }

                        0b10000000 -> {
                            // 2x2 变换矩阵
                            c.xScale = reader.ReadUInt16().toFloat() / 16384.0f
                            c.scale01 = reader.ReadUInt16().toFloat() / 16384.0f
                            c.scale10 = reader.ReadUInt16().toFloat() / 16384.0f
                            c.yScale = reader.ReadUInt16().toFloat() / 16384.0f
                        }
                    }
                    component.add(c)
                    if ((c.flags and 0x20) == 0) break
                }
            }
            glyfArray[index] = glyph
        }
    }

    /**
     * 使用轮廓索引值获取轮廓数据
     */
    fun getGlyfById(glyfId: Int): String? {
        val glyph = glyfArray[glyfId] ?: return null // 过滤不存在的字体轮廓
        return if (glyph.numberOfContours >= 0) {
            // 简单字形
            val simple = glyph.glyphSimple!!
            val dataCount = simple.flags.size
            Array(dataCount) { i -> "${simple.xCoordinates[i]},${simple.yCoordinates[i]}" }
                .joinToString("|")
        } else {
            // 复合字形
            val glyphIdList = glyph.glyphComponent!!.map { g ->
                "{flags:${g.flags},glyphIndex:${g.glyphIndex},arg1:${g.argument1}," +
                    "arg2:${g.argument2},xScale:${g.xScale},scale01:${g.scale01}," +
                    "scale10:${g.scale10},yScale:${g.yScale}}"
            }
            "[" + glyphIdList.joinToString(",") + "]"
        }
    }

    /**
     * 使用 Unicode 值查询轮廓索引
     */
    fun getGlyfIdByUnicode(unicode: Int): Int {
        return unicodeToGlyphId[unicode] ?: 0 // 找不到就返回默认值0
    }

    /**
     * 使用 Unicode 值查询轮廓数据
     */
    fun getGlyfByUnicode(unicode: Int): String? {
        return unicodeToGlyph[unicode]
    }

    /**
     * 使用轮廓数据反查 Unicode 值
     */
    fun getUnicodeByGlyf(glyph: String?): Int {
        val result = if (glyph == null) null else glyphToUnicode[glyph]
        return result ?: 0 // 找不到就返回默认值0
    }

    /**
     * Unicode 空白字符判断
     */
    fun isBlankUnicode(unicode: Int): Boolean {
        return when (unicode) {
            0x0009,     // 水平制表符
            0x0020,     // 空格
            0x00A0,     // 不中断空格
            0x2002,     // En空格
            0x2003,     // Em空格
            0x2007,     // 刚性空格
            0x200A,     // 发音修饰字母的连字符
            0x200B,     // 零宽空格
            0x200C,     // 零宽不连字
            0x200D,     // 零宽连字
            0x202F,     // 狭窄不中断空格
            0x205F      // 中等数学空格
            -> true

            else -> false
        }
    }

    private companion object {
        /** 等价 java String(bytes, US_ASCII)：>0x7F 的字节逐个替换为 U+FFFD */
        fun ByteArray.decodeAscii(): String = CharArray(size) {
            val b = this[it].toInt() and 0xFF
            if (b < 0x80) b.toChar() else '�'
        }.concatToString()
    }
}
