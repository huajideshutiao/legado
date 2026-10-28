/*
 * Copyright (c) 2005, 2010, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version 2
 * along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

// Vendored+patched 溯源 (整体说明见 modules/charset-native/vendor/PROVENANCE.md):
// 原文件 = com.fleeksoft.charset:charset:0.0.8 sources 的
// nonJvmMain/com/fleeksoft/charset/cs/utf/UTF_32Coder.kt (OpenJDK sun.nio.cs.UTF_32Coder
// 移植, Apache-2.0)。换包 com.legado.charset 并将对象改名 Utf32Coder: 依赖 klib (iOS
// 官方 klib / 鸿蒙 ksoup-ohos) 已含同包同名原类, 同包会重类冲突。
// 算法唯一改动: 解码对代理区码点 (U+D800..U+DFFF) 与超范围码点返回 malformed(4) 交
// REPLACE 输出 U+FFFD (上游与 JDK 原版一致经 isBmpCodePoint 放行代理区, 本仓约束
// 非法码点→U+FFFD); 其余逐行同上游。

package com.legado.charset

import com.fleeksoft.charset.Charset
import com.fleeksoft.charset.CharsetDecoder
import com.fleeksoft.charset.CharsetEncoder
import com.fleeksoft.charset.CoderResult
import com.fleeksoft.charset.internal.CoderResultInternal
import com.fleeksoft.io.ByteBuffer
import com.fleeksoft.io.CharBuffer
import com.fleeksoft.io.getInt
import com.fleeksoft.lang.Character

object Utf32Coder {
    internal const val BOM_BIG: Int = 0xFEFF
    internal const val BOM_LITTLE: Int = -0x20000
    internal const val NONE: Int = 0
    internal const val BIG: Int = 1
    internal const val LITTLE: Int = 2

    open class Decoder(cs: Charset, private val expectedBO: Int) : CharsetDecoder(cs, 0.25f, 1.0f) {
        private var currentBO: Int

        init {
            this.currentBO = NONE
        }

        private fun getCP(src: ByteBuffer): Int {
            return if (currentBO == BIG)
                (((src.getInt() and 0xff) shl 24) or
                        ((src.getInt() and 0xff) shl 16) or
                        ((src.getInt() and 0xff) shl 8) or
                        (src.getInt() and 0xff))
            else
                ((src.getInt() and 0xff) or
                        ((src.getInt() and 0xff) shl 8) or
                        ((src.getInt() and 0xff) shl 16) or
                        ((src.getInt() and 0xff) shl 24))
        }

        override fun decodeLoop(byteBuffer: ByteBuffer, charBuffer: CharBuffer): CoderResult {
            if (byteBuffer.remaining() < 4) return CoderResultInternal.UNDERFLOW
            var mark: Int = byteBuffer.position()
            var cp: Int
            try {
                if (currentBO == NONE) {
                    cp = ((byteBuffer.getInt() and 0xff) shl 24) or
                            ((byteBuffer.getInt() and 0xff) shl 16) or
                            ((byteBuffer.getInt() and 0xff) shl 8) or
                            (byteBuffer.getInt() and 0xff)
                    if (cp == BOM_BIG && expectedBO != LITTLE) {
                        currentBO = BIG
                        mark += 4
                    } else if (cp == BOM_LITTLE && expectedBO != BIG) {
                        currentBO = LITTLE
                        mark += 4
                    } else {
                        if (expectedBO == NONE) currentBO = BIG
                        else currentBO = expectedBO
                        byteBuffer.position(mark)
                    }
                }
                while (byteBuffer.remaining() >= 4) {
                    cp = getCP(byteBuffer)
                    // 本仓补丁: 代理区/超范围码点非法, 按 malformed(4) 交 REPLACE 输出 U+FFFD
                    // (上游 isBmpCodePoint 分支放行代理区)
                    if (cp < 0xD800 || cp in 0xE000..0xFFFF) {
                        if (!charBuffer.hasRemaining()) return CoderResultInternal.OVERFLOW
                        mark += 4
                        charBuffer.put(cp.toChar())
                    } else if (cp in 0x10000..0x10FFFF) {
                        if (charBuffer.remaining() < 2) return CoderResultInternal.OVERFLOW
                        mark += 4
                        charBuffer.put(Character.highSurrogate(cp))
                        charBuffer.put(Character.lowSurrogate(cp))
                    } else {
                        return CoderResultInternal.malformedForLength(4)
                    }
                }
                return CoderResultInternal.UNDERFLOW
            } finally {
                byteBuffer.position(mark)
            }
        }

        override fun implReset() {
            currentBO = NONE
        }
    }

    open class Encoder(cs: Charset, private val byteOrder: Int, doBOM: Boolean) : CharsetEncoder(
        cs, 4.0f,
        if (doBOM) 8.0f else 4.0f,
        if (byteOrder == BIG)
            byteArrayOf(0.toByte(), 0.toByte(), 0xff.toByte(), 0xfd.toByte())
        else
            byteArrayOf(0xfd.toByte(), 0xff.toByte(), 0.toByte(), 0.toByte())
    ) {
        private var doBOM = false
        private var doneBOM = true

        protected fun put(cp: Int, dst: ByteBuffer) {
            if (byteOrder == BIG) {
                dst.put((cp shr 24).toByte())
                dst.put((cp shr 16).toByte())
                dst.put((cp shr 8).toByte())
                dst.put(cp.toByte())
            } else {
                dst.put(cp.toByte())
                dst.put((cp shr 8).toByte())
                dst.put((cp shr 16).toByte())
                dst.put((cp shr 24).toByte())
            }
        }

        init {
            this.doBOM = doBOM
            this.doneBOM = !doBOM
        }

        override fun encodeLoop(inBuffer: CharBuffer, outBuffer: ByteBuffer): CoderResult {
            var mark: Int = inBuffer.position()
            if (!doneBOM && inBuffer.hasRemaining()) {
                if (outBuffer.remaining() < 4) return CoderResultInternal.OVERFLOW
                put(BOM_BIG, outBuffer)
                doneBOM = true
            }
            try {
                while (inBuffer.hasRemaining()) {
                    val c: Char = inBuffer.get()
                    if (!Character.isSurrogate(c)) {
                        if (outBuffer.remaining() < 4) return CoderResultInternal.OVERFLOW
                        mark++
                        put(c.code, outBuffer)
                    } else if (Character.isHighSurrogate(c)) {
                        if (!inBuffer.hasRemaining()) return CoderResultInternal.UNDERFLOW
                        val low: Char = inBuffer.get()
                        if (Character.isLowSurrogate(low)) {
                            if (outBuffer.remaining() < 4) return CoderResultInternal.OVERFLOW
                            mark += 2
                            put(Character.toCodePoint(c, low), outBuffer)
                        } else {
                            return CoderResultInternal.malformedForLength(1)
                        }
                    } else {
                        // assert Character.isLowSurrogate(c);
                        return CoderResultInternal.malformedForLength(1)
                    }
                }
                return CoderResultInternal.UNDERFLOW
            } finally {
                inBuffer.position(mark)
            }
        }

        override fun implReset() {
            doneBOM = !doBOM
        }
    }
}
