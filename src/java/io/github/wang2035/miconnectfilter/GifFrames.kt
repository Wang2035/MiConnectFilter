package io.github.wang2035.miconnectfilter

/**
 * 纯 Kotlin GIF 解码器 + 播放时序计算（无 Android 依赖，可 JVM 单测）。
 *
 * 为什么不用系统 AnimatedImageDrawable：它没有变速、没有往返播放，
 * 作卡片 background 时帧推进还依赖可见性回调转发（v1.23 起反复踩坑）。
 * 自己解出帧序后，循环/播一次停/往返、0.5–2 倍速都由 [GifTimeline] 精确控制。
 */
class GifFrame(val pixels: IntArray, val width: Int, val height: Int, val delayMs: Int)

object GifDecoder {

    fun isGif(bytes: ByteArray): Boolean =
        bytes.size >= 6 &&
            bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte() &&
            bytes[3] == 0x38.toByte() && (bytes[4] == 0x37.toByte() || bytes[4] == 0x39.toByte()) &&
            bytes[5] == 0x61.toByte()

    private class Reader(private val b: ByteArray) {
        var pos = 0
        fun u8(): Int = if (pos < b.size) b[pos++].toInt() and 0xFF else 0
        fun u16(): Int = u8() or (u8() shl 8)
        fun skip(n: Int) {
            pos = minOf(b.size, pos + n)
        }

        fun colorTable(entries: Int): IntArray {
            val out = IntArray(entries)
            for (i in 0 until entries) {
                val r = u8(); val g = u8(); val bl = u8()
                out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
            }
            return out
        }

        /** 读完所有数据子块并拼起来；返回拼接字节。 */
        fun subBlocks(): ByteArray {
            val chunks = ArrayList<ByteArray>()
            var total = 0
            while (true) {
                val n = u8()
                if (n == 0 || pos >= b.size) break
                val take = minOf(n, b.size - pos)
                val c = b.copyOfRange(pos, pos + take)
                chunks.add(c)
                total += take
                pos += take
                if (take < n) break
            }
            val out = ByteArray(total)
            var o = 0
            for (c in chunks) {
                c.copyInto(out, o)
                o += c.size
            }
            return out
        }

        fun skipSubBlocks() {
            while (true) {
                val n = u8()
                if (n == 0) break
                skip(n)
            }
        }
    }

    private class Gce(val delayMs: Int = 100, val disposal: Int = 0, val transIndex: Int = -1)

    /** 只扫结构数帧数 + 总时长（延迟钳制同 decode），不做 LZW 解码。导入时计数用，快得多。 */
    fun scanInfo(bytes: ByteArray): Pair<Int, Long> {
        if (!isGif(bytes)) return 0 to 0L
        val r = Reader(bytes)
        r.pos = 6
        val w = r.u16()
        val h = r.u16()
        if (w <= 0 || h <= 0 || w > 4096 || h > 4096) return 0 to 0L
        val packed = r.u8()
        r.u8()
        r.u8()
        if (packed and 0x80 != 0) r.skip(3 * (1 shl ((packed and 0x07) + 1)))
        var count = 0
        var total = 0L
        var delayMs = 100
        while (r.pos < bytes.size) {
            when (r.u8()) {
                0x3B -> return count to total
                0x21 -> {
                    val label = r.u8()
                    if (label == 0xF9) {
                        r.u8() // 块大小
                        r.u8() // 打包字段
                        delayMs = r.u16() * 10
                        r.u8() // 透明索引
                        r.u8() // 块结束
                    } else {
                        r.skipSubBlocks()
                    }
                }
                0x2C -> {
                    r.skip(8) // x、y、w、h
                    val pk = r.u8()
                    if (pk and 0x80 != 0) r.skip(3 * (1 shl ((pk and 0x07) + 1)))
                    r.u8() // LZW 最小码长
                    r.skipSubBlocks()
                    count++
                    total += if (delayMs < 10) 100 else delayMs
                    delayMs = 100
                }
                else -> return count to total
            }
        }
        return count to total
    }

    /**
     * 解码全部帧（合成后的整幅画面）。maxFrames 截断过长动图；
     * maxDim 对整幅做整数倍最近邻缩小，控制内存。
     */
    fun decode(bytes: ByteArray, maxFrames: Int = 120, maxDim: Int = 1080): List<GifFrame> {
        if (!isGif(bytes)) return emptyList()
        val r = Reader(bytes)
        r.pos = 6
        val w = r.u16()
        val h = r.u16()
        if (w <= 0 || h <= 0 || w > 4096 || h > 4096) return emptyList()
        val packed = r.u8()
        r.u8() // 背景色索引（画布用透明底，不需要）
        r.u8() // 宽高比
        val gct = if (packed and 0x80 != 0) r.colorTable(1 shl ((packed and 0x07) + 1)) else null

        val canvas = IntArray(w * h)
        var gce = Gce()
        var snapshot: IntArray? = null
        val frames = ArrayList<GifFrame>()

        while (r.pos < bytes.size) {
            when (r.u8()) {
                0x3B -> return finish(frames)
                0x21 -> {
                    val label = r.u8()
                    if (label == 0xF9) {
                        r.u8() // 块大小（恒 4）
                        val p = r.u8()
                        val delay = r.u16() * 10
                        val ti = r.u8()
                        r.u8() // 块结束
                        gce = Gce(
                            delayMs = delay,
                            disposal = (p shr 2) and 0x07,
                            transIndex = if (p and 0x01 != 0) ti else -1,
                        )
                    } else {
                        r.skipSubBlocks()
                    }
                }
                0x2C -> {
                    val fx = r.u16(); val fy = r.u16(); val fw = r.u16(); val fh = r.u16()
                    val pk = r.u8()
                    val lct = if (pk and 0x80 != 0) r.colorTable(1 shl ((pk and 0x07) + 1)) else null
                    val table = lct ?: gct
                    if (table == null || fw <= 0 || fh <= 0) {
                        // 没有颜色表无法解这一帧，跳过数据
                        r.u8()
                        r.skipSubBlocks()
                        continue
                    }
                    val interlace = pk and 0x40 != 0
                    val minCode = r.u8()
                    val data = r.subBlocks()
                    val indices = lzwDecode(data, minCode, fw * fh) ?: continue
                    if (gce.disposal == 3) snapshot = canvas.copyOf()
                    // 行序（隔行扫描时是 4 趟交错序）
                    val rowOrder = IntArray(fh) { it }
                    if (interlace) {
                        var o = 0
                        val starts = intArrayOf(0, 4, 2, 1)
                        val steps = intArrayOf(8, 8, 4, 2)
                        for (pass in 0 until 4) {
                            var row = starts[pass]
                            while (row < fh) {
                                rowOrder[o++] = row
                                row += steps[pass]
                            }
                        }
                    }
                    for (sy in 0 until fh) {
                        val dy = fy + rowOrder[sy]
                        if (dy >= h) continue
                        var src = sy * fw
                        var dst = dy * w + fx
                        for (sx in 0 until fw) {
                            val cx = fx + sx
                            if (cx < w && src < indices.size) {
                                val idx = indices[src]
                                if (idx != gce.transIndex && idx < table.size) {
                                    canvas[dst] = table[idx]
                                }
                            }
                            src++
                            dst++
                        }
                    }
                    val delay = if (gce.delayMs < 10) 100 else gce.delayMs
                    frames.add(GifFrame(scaleDown(canvas.copyOf(), w, h, maxDim), scaledDim(w, maxDim, w, h), scaledDim(h, maxDim, w, h), delay))
                    when (gce.disposal) {
                        2 -> {
                            for (yy in fy until minOf(h, fy + fh)) {
                                var i = yy * w + fx
                                for (xx in fx until minOf(w, fx + fw)) {
                                    canvas[i] = 0
                                    i++
                                }
                            }
                        }
                        3 -> snapshot?.copyInto(canvas)
                    }
                    gce = Gce()
                    if (frames.size >= maxFrames) return finish(frames)
                }
                else -> return finish(frames)
            }
        }
        return finish(frames)
    }

    private fun finish(frames: List<GifFrame>): List<GifFrame> = frames

    private fun scaledDim(dim: Int, maxDim: Int, w: Int, h: Int): Int {
        val m = maxOf(w, h)
        if (m <= maxDim) return dim
        val factor = (m + maxDim - 1) / maxDim
        return maxOf(1, dim / factor)
    }

    private fun scaleDown(pixels: IntArray, w: Int, h: Int, maxDim: Int): IntArray {
        val m = maxOf(w, h)
        if (m <= maxDim) return pixels
        val factor = (m + maxDim - 1) / maxDim
        val nw = maxOf(1, w / factor)
        val nh = maxOf(1, h / factor)
        val out = IntArray(nw * nh)
        for (y in 0 until nh) {
            var src = (y * factor) * w
            var dst = y * nw
            for (x in 0 until nw) {
                out[dst++] = pixels[src]
                src += factor
            }
        }
        return out
    }

    /** 帧太多时抽帧（被抽帧的时长并入前一帧，总时长不变），控制 hook 进程内存。 */
    fun thinFrames(frames: List<GifFrame>, maxTotalPx: Long): List<GifFrame> {
        if (frames.isEmpty()) return frames
        val px = frames[0].width.toLong() * frames[0].height.toLong()
        if (px <= 0 || px * frames.size <= maxTotalPx) return frames
        val out = ArrayList<GifFrame>()
        var i = 0
        var carry = 0
        while (i < frames.size) {
            val f = frames[i]
            out.add(GifFrame(f.pixels, f.width, f.height, f.delayMs + carry))
            carry = if (i + 1 < frames.size) frames[i + 1].delayMs else 0
            i += 2
        }
        if (carry > 0 && out.isNotEmpty()) {
            val last = out[out.size - 1]
            out[out.size - 1] = GifFrame(last.pixels, last.width, last.height, last.delayMs + carry)
        }
        return thinFrames(out, maxTotalPx)
    }

    /** GIF LZW 解压，输出颜色索引数组（长度不足时以 0 补齐）。 */
    private fun lzwDecode(data: ByteArray, minCodeSize: Int, expected: Int): IntArray? {
        if (minCodeSize < 2 || minCodeSize > 8) return null
        val clear = 1 shl minCodeSize
        val eoi = clear + 1
        val prefix = IntArray(4096) { -1 }
        val suffix = IntArray(4096)
        for (i in 0 until clear) suffix[i] = i // 根码元的字节就是它本身
        val stack = IntArray(4096 + 1)
        val out = IntArray(expected)
        var outPos = 0
        var codeSize = minCodeSize + 1
        var available = eoi + 1
        var bitPos = 0
        var prevCode = -1
        var prevFirst = 0

        fun readCode(): Int {
            var code = 0
            var shift = 0
            var need = codeSize
            while (need > 0) {
                val byteIdx = bitPos / 8
                if (byteIdx >= data.size) return -1
                val bitInByte = bitPos % 8
                val take = minOf(need, 8 - bitInByte)
                val bits = (data[byteIdx].toInt() and 0xFF) shr bitInByte and ((1 shl take) - 1)
                code = code or (bits shl shift)
                shift += take
                need -= take
                bitPos += take
            }
            return code
        }

        while (true) {
            val code = readCode()
            if (code < 0 || code == eoi) break
            if (code == clear) {
                codeSize = minCodeSize + 1
                available = eoi + 1
                prevCode = -1
                continue
            }
            var inCode = code
            var sp = 0
            if (code > available) break // 数据损坏
            if (code == available && prevCode >= 0) {
                // KwKwK：新串 = 前串 + 前串首字节
                stack[sp++] = prevFirst
                inCode = prevCode
            }
            var c = inCode
            while (c > clear) {
                if (sp >= stack.size) break
                stack[sp++] = suffix[c]
                c = prefix[c]
                if (c < 0) break
            }
            if (c < 0) break
            val first = suffix[c]
            stack[sp++] = first
            while (sp > 0) {
                if (outPos < out.size) out[outPos++] = stack[--sp] else {
                    sp = 0
                    break
                }
            }
            if (prevCode >= 0 && available < 4096) {
                prefix[available] = prevCode
                suffix[available] = first
                available++
                if (available > (1 shl codeSize) - 1 && codeSize < 12) codeSize++
            }
            prevFirst = first
            prevCode = code
        }
        return out
    }
}

/**
 * 播放时间轴：把「有效毫秒」（真实时间 × 速度百分比 / 100）映射到帧序号。
 * 往返（PINGPONG）的序列是 0,1,…,n-1,n-2,…,1，周期为正放+倒放之和。
 */
class GifTimeline(delaysMs: IntArray, val mode: Int) {

    val seqFrames: IntArray
    private val seqStart: LongArray // 每段起始（有效 ms），末位 = 周期总长
    val cycleMs: Long
    val frameCount: Int = delaysMs.size

    init {
        val n = delaysMs.size
        val seq = ArrayList<Int>()
        if (n == 1) {
            seq.add(0)
        } else if (mode == BgOpt.MODE_PINGPONG) {
            for (i in 0 until n) seq.add(i)
            for (i in n - 2 downTo 1) seq.add(i)
        } else {
            for (i in 0 until n) seq.add(i)
        }
        seqFrames = seq.toIntArray()
        seqStart = LongArray(seq.size + 1)
        var acc = 0L
        for (i in seq.indices) {
            seqStart[i] = acc
            acc += maxOf(1, delaysMs[seq[i]]).toLong()
        }
        seqStart[seq.size] = acc
        cycleMs = acc
    }

    private fun slotAt(posMs: Long): Int {
        // 二分找最后一个 start <= pos 的段
        var lo = 0
        var hi = seqFrames.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (seqStart[mid] <= posMs) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** 有效时间 -> 帧序号。ONCE 超时后停在最后一帧。 */
    fun frameAt(effMs: Long): Int {
        if (frameCount == 0 || cycleMs <= 0L) return 0
        if (mode == BgOpt.MODE_ONCE) {
            if (effMs >= cycleMs) return seqFrames[seqFrames.size - 1]
            return seqFrames[slotAt(effMs)]
        }
        val pos = ((effMs % cycleMs) + cycleMs) % cycleMs
        return seqFrames[slotAt(pos)]
    }

    /**
     * 从 effMs 所在段开始，沿播放序列往后取至多 count 个帧序号：
     * 往返模式逆向段返回递减序号，循环模式绕回开头，播一次模式到末尾停。
     * 预解码必须按这个方向走，只按帧号往前预取会让往返的逆向段永远等不到帧。
     */
    fun upcoming(effMs: Long, count: Int): List<Int> {
        if (frameCount == 0 || cycleMs <= 0L || seqFrames.isEmpty() || count <= 0) {
            return emptyList()
        }
        if (mode == BgOpt.MODE_ONCE && effMs >= cycleMs) return emptyList()
        val pos = ((effMs % cycleMs) + cycleMs) % cycleMs
        var slot = slotAt(pos)
        val total = seqFrames.size
        val maxSteps = if (mode == BgOpt.MODE_ONCE) total - slot else total
        val out = ArrayList<Int>(minOf(count, maxSteps))
        var steps = 0
        while (out.size < count && steps < maxSteps) {
            val f = seqFrames[slot]
            if (out.isEmpty() || out[out.size - 1] != f) out.add(f)
            slot = (slot + 1) % total
            steps++
        }
        return out
    }

    /** 有效时间 -> 下一次换帧的有效时间点（ONCE 已结束返回 Long.MAX_VALUE）。 */
    fun nextChangeAt(effMs: Long): Long {
        if (frameCount == 0 || cycleMs <= 0L) return Long.MAX_VALUE
        if (mode == BgOpt.MODE_ONCE) {
            if (effMs >= cycleMs) return Long.MAX_VALUE
            val slot = slotAt(effMs)
            return seqStart[slot + 1]
        }
        val pos = ((effMs % cycleMs) + cycleMs) % cycleMs
        val slot = slotAt(pos)
        return effMs - pos + seqStart[slot + 1]
    }

    fun isFinished(effMs: Long): Boolean = mode == BgOpt.MODE_ONCE && effMs >= cycleMs

    companion object {
        /** 真实时间（ms）按速度折算成有效时间。 */
        fun effective(tMs: Long, speedPct: Int): Long = tMs * speedPct / 100
    }
}
