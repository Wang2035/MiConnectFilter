import io.github.wang2035.miconnectfilter.*
import java.io.File

var gifFailures = 0
fun gcheck(name: String, cond: Boolean) {
    if (cond) println("PASS $name") else {
        println("FAIL $name")
        gifFailures++
    }
}

fun argb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

fun main() {
    // ---- GIF (a)：3 帧纯色、不同时长 ----
    val a = GifDecoder.decode(File("/tmp/test_a.gif").readBytes())
    gcheck("a: 3 frames", a.size == 3)
    if (a.size == 3) {
        gcheck("a: delays", a.map { it.delayMs } == listOf(100, 200, 300))
        gcheck("a: size", a[0].width == 10 && a[0].height == 8)
        gcheck("a: red", a[0].pixels.all { it == argb(255, 0, 0) })
        gcheck("a: green", a[1].pixels.all { it == argb(0, 255, 0) })
        gcheck("a: blue", a[2].pixels.all { it == argb(0, 0, 255) })
    }

    // ---- GIF (b)：噪声帧，和 PIL 回读逐像素比对（考验 LZW 码长增长） ----
    val b = GifDecoder.decode(File("/tmp/test_b.gif").readBytes())
    val exp = File("/tmp/test_b_expected.bin").readBytes()
    gcheck("b: 4 frames", b.size == 4)
    if (b.size == 4) {
        var same = true
        for (fi in 0 until 4) {
            val frame = b[fi]
            if (frame.width != 64 || frame.height != 64) {
                same = false
                break
            }
            for (i in 0 until 64 * 64) {
                val o = (fi * 64 * 64 + i) * 3
                val want = argb(
                    exp[o].toInt() and 0xFF,
                    exp[o + 1].toInt() and 0xFF,
                    exp[o + 2].toInt() and 0xFF,
                )
                if (frame.pixels[i] != want) {
                    same = false
                    break
                }
            }
            if (!same) break
        }
        gcheck("b: pixels match PIL", same)
        gcheck("b: delays", b.all { it.delayMs == 50 })
    }

    // ---- GIF (c)：第二帧局部覆盖 + 透明，合成结果 ----
    val c = GifDecoder.decode(File("/tmp/test_c.gif").readBytes())
    gcheck("c: 2 frames", c.size == 2)
    if (c.size == 2) {
        val f = c[1]
        gcheck("c: corner stays red", f.pixels[0] == argb(255, 0, 0))
        gcheck("c: center green", f.pixels[5 * 12 + 5] == argb(0, 255, 0))
        gcheck("c: edge stays red", f.pixels[11 * 12 + 11] == argb(255, 0, 0))
    }

    // ---- 非 GIF 输入 ----
    gcheck("not gif", GifDecoder.decode(byteArrayOf(1, 2, 3)).isEmpty())
    gcheck("isGif", GifDecoder.isGif(File("/tmp/test_a.gif").readBytes()))

    // ---- scanInfo：只扫结构，与全解码的帧数/总时长一致 ----
    for ((tag, file) in listOf("a" to "/tmp/test_a.gif", "b" to "/tmp/test_b.gif", "c" to "/tmp/test_c.gif")) {
        val bytes = File(file).readBytes()
        val (cnt, total) = GifDecoder.scanInfo(bytes)
        val dec = GifDecoder.decode(bytes)
        gcheck("scan $tag count", cnt == dec.size)
        gcheck("scan $tag total", total == dec.sumOf { it.delayMs.toLong() })
    }
    gcheck("scan not gif", GifDecoder.scanInfo(byteArrayOf(1, 2, 3)) == (0 to 0L))

    // ---- 抽帧：总时长守恒 ----
    val big = (0 until 4).map { GifFrame(IntArray(100 * 100) { it }, 100, 100, (it + 1) * 10) }
    val thinned = GifDecoder.thinFrames(big, 20_000L)
    gcheck("thin: count", thinned.size == 2)
    gcheck("thin: total delay kept", thinned.sumOf { it.delayMs } == 100)

    // ---- 时间轴 ----
    val delays = intArrayOf(100, 200, 300)
    val loop = GifTimeline(delays, BgOpt.MODE_LOOP)
    gcheck("loop cycle", loop.cycleMs == 600L)
    gcheck("loop t0", loop.frameAt(0) == 0)
    gcheck("loop t99", loop.frameAt(99) == 0)
    gcheck("loop t100", loop.frameAt(100) == 1)
    gcheck("loop t300", loop.frameAt(300) == 2)
    gcheck("loop t600 wraps", loop.frameAt(600) == 0)
    gcheck("loop next", loop.nextChangeAt(0) == 100L && loop.nextChangeAt(100) == 300L)

    val once = GifTimeline(delays, BgOpt.MODE_ONCE)
    gcheck("once mid", once.frameAt(150) == 1)
    gcheck("once end stays last", once.frameAt(100_000) == 2)
    gcheck("once finished", once.isFinished(600) && !once.isFinished(599))
    gcheck("once next end", once.nextChangeAt(600) == Long.MAX_VALUE)

    val pp = GifTimeline(delays, BgOpt.MODE_PINGPONG)
    gcheck("pp cycle", pp.cycleMs == 800L)
    gcheck("pp fwd", pp.frameAt(0) == 0 && pp.frameAt(100) == 1 && pp.frameAt(300) == 2)
    gcheck("pp back", pp.frameAt(600) == 1 && pp.frameAt(799) == 1)
    gcheck("pp wraps", pp.frameAt(800) == 0)
    gcheck("pp single", GifTimeline(intArrayOf(100), BgOpt.MODE_PINGPONG).frameAt(500) == 0)
    gcheck("effective speed", GifTimeline.effective(1000, 150) == 1500L)

    // ---- BgOpt 序列化 ----
    val opt = BgOpt(mode = 2, speedPct = 150, focusX = 250, focusY = 750, scalePct = 130)
    gcheck("opt roundtrip", BgOpt.parse(opt.render()) == opt)
    gcheck("opt default", BgOpt.parse(null) == BgOpt.DEFAULT && BgOpt.parse("junk") == BgOpt.DEFAULT)
    val map = mapOf("all" to opt, "AABBCC112233" to BgOpt(mode = 1))
    gcheck("opt map roundtrip", BgOpt.parseMap(BgOpt.renderMap(map)) == map)

    // ---- 配置全文带 bg_opts 往返 ----
    val rendered = FilterConfig.render(true, setOf("AA:BB:CC:11:22:33"), 0L, false, mapOf("all" to 3L), map)
    val parsed = FilterConfig.parse(rendered)
    gcheck("conf bg_opts roundtrip", parsed.bgOpts == map && parsed.bgOptFor("all") == opt)
    gcheck("conf bgOptFor default", parsed.bgOptFor("112233445566") == BgOpt.DEFAULT)

    if (gifFailures > 0) {
        println("GIF TESTS FAILED: $gifFailures")
        kotlin.system.exitProcess(1)
    }
    println("ALL GIF TESTS PASSED")
}
