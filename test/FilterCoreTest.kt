import com.miconnect.filter.*

var failures = 0
fun check(name: String, cond: Boolean) {
    if (cond) println("PASS $name") else { println("FAIL $name"); failures++ }
}

fun writeTmp(content: String): java.io.File {
    val f = java.io.File.createTempFile("mcf-test", ".conf")
    f.writeText(content)
    f.deleteOnExit()
    return f
}

fun main() {
    val now = 1_700_000_000_000L
    val myMac = "AA:BB:CC:11:22:33"
    val otherMac = "11:22:33:44:55:66"

    // --- MAC normalize ---
    check("normalize colon", MacUtil.normalize("aa:bb:cc:11:22:33") == myMac)
    check("normalize dash", MacUtil.normalize("AA-BB-CC-11-22-33") == myMac)
    check("normalize plain", MacUtil.normalize("aabbcc112233") == myMac)
    check("normalize spaces", MacUtil.normalize(" AA BB CC 11 22 33 ") == myMac)
    check("normalize invalid short", MacUtil.normalize("AA:BB:CC") == null)
    check("normalize invalid hex", MacUtil.normalize("GG:BB:CC:11:22:33") == null)
    check("normalize null", MacUtil.normalize(null) == null)
    check("extract macs", MacUtil.extractMacs("dev $otherMac and aa-bb-cc-11-22-33 x") ==
        listOf(otherMac, myMac))
    check("extract none", MacUtil.extractMacs("no mac here").isEmpty())

    // --- config parse/render ---
    val cfgText = FilterConfig.render(true, setOf(myMac.lowercase()), now + 60_000, false)
    val cfg = FilterConfig.parse(cfgText)
    check("render/parse enabled", cfg.enabled)
    check("render/parse whitelist normalized", cfg.whitelist == setOf(myMac))
    check("render/parse bypass", cfg.bypassUntilMs == now + 60_000)
    val cfg2 = FilterConfig.parse("enabled=0\nwhitelist=$otherMac, $myMac\nbypass_until=0\ndebug=1\n")
    check("parse disabled", !cfg2.enabled)
    check("parse whitelist 2", cfg2.whitelist == setOf(otherMac, myMac))
    check("parse debug", cfg2.debug)

    val base = FilterConfig(enabled = true, whitelist = setOf(myMac), bypassUntilMs = 0)
    fun probe(mac: String? = otherMac, bonded: Boolean? = false, name: String? = "Redmi Buds 5",
              xiaomi: Boolean = true, popup: Boolean = true, strong: Boolean = false) =
        Probe(mac, bonded, name, xiaomi, popup, strong)

    // --- decision matrix ---
    check("whitelist allowed", FilterCore.decide(base, probe(mac = myMac), now) == Decision.ALLOW)
    check("bonded allowed (paired flows, non-popup)", FilterCore.decide(base, probe(bonded = true, popup = false), now) == Decision.ALLOW)
    check("bonded popup blocked (strict hardcoded)", FilterCore.decide(base, probe(bonded = true), now) == Decision.BLOCK)
    check("bonded allowed even no name", FilterCore.decide(base,
        Probe(otherMac, true, null, false, true), now) == Decision.ALLOW)
    check("other xiaomi unbonded popup blocked", FilterCore.decide(base, probe(), now) == Decision.BLOCK)
    check("other xiaomi not popup passes", FilterCore.decide(base, probe(popup = false), now) == Decision.PASS)
    check("non-xiaomi popup passes", FilterCore.decide(base,
        probe(name = "Galaxy Buds2", xiaomi = false), now) == Decision.PASS)
    check("xiaomi phone name not earbud passes", FilterCore.decide(base,
        probe(name = "Xiaomi 15 Pro", xiaomi = false), now) == Decision.PASS)
    check("name-only xiaomi earbud blocked", FilterCore.decide(base,
        Probe(otherMac, false, "Xiaomi Buds 4 Pro", false, true), now) == Decision.BLOCK)
    check("no mac + popup + not strong passes", FilterCore.decide(base,
        Probe(null, null, "Redmi Buds 4", true, true, false), now) == Decision.PASS)
    check("no mac + strong discovery blocked", FilterCore.decide(base,
        Probe(null, false, "Redmi Buds 4", true, true, true), now) == Decision.BLOCK)
    check("no mac + bonded passes as allow", FilterCore.decide(base,
        Probe(null, true, "Redmi Buds 4", true, true, true), now) == Decision.ALLOW)
    check("disabled passes all", FilterCore.decide(base.copy(enabled = false), probe(), now) == Decision.PASS)

    // --- bypass (temporary allow for adding new device) ---
    val bypassCfg = base.copy(bypassUntilMs = now + 5 * 60_000)
    check("bypass active allows other xiaomi", FilterCore.decide(bypassCfg, probe(), now) == Decision.ALLOW)
    check("bypass still active at +4:59", FilterCore.decide(bypassCfg, probe(), now + 299_000) == Decision.ALLOW)
    check("bypass expired at +5:00 blocks again", FilterCore.decide(bypassCfg, probe(), now + 300_000) == Decision.BLOCK)
    check("bypass minutes clamp low", FilterCore.clampBypassMinutes(0) == 1)
    check("bypass minutes clamp high", FilterCore.clampBypassMinutes(30) == 5)
    check("bypass minutes 3 ok", FilterCore.clampBypassMinutes(3) == 3)

    // --- earbud name detection ---
    check("name Redmi Buds", FilterCore.isXiaomiEarbudName("Redmi Buds 5 Pro"))
    check("name Xiaomi Buds", FilterCore.isXiaomiEarbudName("Xiaomi Buds 4"))
    check("name FlipBuds", FilterCore.isXiaomiEarbudName("FlipBuds Pro"))
    check("name Mi True Wireless", FilterCore.isXiaomiEarbudName("Mi True Wireless Earbuds Basic"))
    check("name POCO Buds", FilterCore.isXiaomiEarbudName("POCO Buds X1"))
    check("name xiaomi phone no", !FilterCore.isXiaomiEarbudName("Xiaomi 15"))
    check("name galaxy buds no", !FilterCore.isXiaomiEarbudName("Galaxy Buds2 Pro"))
    check("name airpods no", !FilterCore.isXiaomiEarbudName("AirPods Pro"))
    check("name null no", !FilterCore.isXiaomiEarbudName(null))

    // --- targets (OS3 adaptation + recommended scope) ---
    check("target connectionshare", Targets.PACKAGES.contains("com.miui.connectionshare"))
    check("target milink (OS3 interconnect)", Targets.PACKAGES.contains("com.milink.service"))
    check("target bluetoothext", Targets.PACKAGES.contains("com.miui.bluetoothext"))
    check("target xiaomi.bluetooth", Targets.PACKAGES.contains("com.xiaomi.bluetooth"))
    check("target android.bluetooth", Targets.PACKAGES.contains("com.android.bluetooth"))
    check("target mi_connect_service", Targets.PACKAGES.contains("com.xiaomi.mi_connect_service"))
    check("targets exactly 6", Targets.PACKAGES.size == 6)
    check("labels for all targets", Targets.LABELS.keys == Targets.PACKAGES)
    check("recommended is all supported", Targets.RECOMMENDED == Targets.PACKAGES && Targets.RECOMMENDED.size == 6)
    check("recommended subset of supported", Targets.PACKAGES.containsAll(Targets.RECOMMENDED))
    check("target does not include system framework", !Targets.PACKAGES.contains("android"))

    // --- broadcast config factory ---
    val bc = FilterConfig.of(false, "aa:bb:cc:11:22:33, $otherMac", -5L, true)
    check("bus config enabled false", !bc.enabled)
    check("bus config whitelist", bc.whitelist == setOf(myMac, otherMac))
    check("bus config bypass clamped", bc.bypassUntilMs == 0L)
    check("bus config debug", bc.debug)
    val bc2 = FilterConfig.of(true, "", now + 1000, false)
    check("bus bypass decides allow", FilterCore.decide(bc2,
        Probe(otherMac, false, "Redmi Buds 5", true, true), now) == Decision.ALLOW)

    // --- reasons (v1.6 diagnostics) ---
    check("reason disabled", FilterCore.decideWithReason(base.copy(enabled = false), probe(), now).second == "disabled")
    check("reason bypass", FilterCore.decideWithReason(bypassCfg, probe(), now).second == "bypass-active")
    check("reason bonded", FilterCore.decideWithReason(base, probe(bonded = true, popup = false), now).second == "bonded")
    check("reason whitelisted", FilterCore.decideWithReason(base, probe(mac = myMac, bonded = null), now).second == "whitelisted")
    check("reason unbonded block", FilterCore.decideWithReason(base, probe(), now).second == "unbonded-xiaomi-popup")
    check("reason not popup", FilterCore.decideWithReason(base, probe(popup = false), now).second == "not-popup")
    check("reason unknown pass", FilterCore.decideWithReason(base,
        Probe(null, null, "Redmi Buds 4", true, true, false), now).second == "unknown-pass")

    // --- strict hardcoded (v1.14) ---
    check("strict bonded non-popup allowed", FilterCore.decide(base, probe(bonded = true, popup = false), now) == Decision.ALLOW)
    check("strict bonded whitelisted allowed", FilterCore.decide(
        base.copy(whitelist = setOf(otherMac)), probe(mac = otherMac, bonded = true), now) == Decision.ALLOW)
    check("strict reason", FilterCore.decideWithReason(base, probe(bonded = true), now).second == "strict-bonded-not-whitelisted")

    // --- paired list grouping (v1.17: same-device multi-address merge, like system settings) ---
    val g1 = PairedGrouping.group(listOf(
        PairedEntry("布洛芬缓释胶囊2粒/盒", "00:BB:43:1C:B8:9C"),
        PairedEntry("布洛芬缓释胶囊2粒/盒", "00:BB:43:1C:F9:EC"),
        PairedEntry("REDMI Buds 8 青春版", "A0:90:B5:DB:48:0D"),
    ))
    check("group same-name merges", g1.size == 2 && g1[0].size == 2)
    check("group keeps macs", g1[0].map { it.mac } == listOf("00:BB:43:1C:B8:9C", "00:BB:43:1C:F9:EC"))
    check("group other separate", g1[1].single().mac == "A0:90:B5:DB:48:0D")
    val g2 = PairedGrouping.group(listOf(
        PairedEntry("A", "11:11:11:11:11:11"),
        PairedEntry("B", "22:22:22:22:22:22"),
        PairedEntry("A", "33:33:33:33:33:33"),
    ))
    check("group non-adjacent merges, order kept", g2.size == 2 && g2[0].map { it.mac } ==
        listOf("11:11:11:11:11:11", "33:33:33:33:33:33") && g2[1].single().name == "B")
    val g3 = PairedGrouping.group(listOf(
        PairedEntry(null, "44:44:44:44:44:44"),
        PairedEntry("  ", "55:55:55:55:55:55"),
        PairedEntry(null, "66:66:66:66:66:66"),
    ))
    check("group blank names never merge", g3.size == 3)
    check("group empty", PairedGrouping.group(emptyList()).isEmpty())
    check("wl status all", PairedGrouping.status(listOf("A", "B"), setOf("A", "B", "C")) == PairedGrouping.WlStatus.ALL)
    check("wl status partial", PairedGrouping.status(listOf("A", "B"), setOf("A")) == PairedGrouping.WlStatus.PARTIAL)
    check("wl status none", PairedGrouping.status(listOf("A", "B"), setOf("C")) == PairedGrouping.WlStatus.NONE)
    check("wl status empty macs", PairedGrouping.status(emptyList(), setOf("A")) == PairedGrouping.WlStatus.NONE)

    println(if (failures == 0) "ALL TESTS PASSED" else "$failures FAILURES")
    if (failures > 0) kotlin.system.exitProcess(1)
}
