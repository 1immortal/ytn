package app.truenascompanion.data.wol

import app.truenascompanion.data.model.NetInterface
import app.truenascompanion.data.model.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 1.12.0: Wake-on-LAN settings of one server.
 *
 * [mac] is the NIC's hardware address (from `interface.query` → `state.hardware_link_address`, the permanent address,
 * falling back to `state.link_address`). [broadcast] is the subnet broadcast of the chosen interface's IPv4 alias; the
 * limited broadcast 255.255.255.255 is always sent too. [relay] is an optional `host:port` (a router or a Raspberry Pi
 * that forwards WoL packets, or a directed broadcast the router lets through).
 */
@Serializable
data class WolConfig(
    val mac: String,
    val interfaceName: String? = null,
    val broadcast: String? = null,
    val port: Int = 9,
    val relay: String? = null,
)

object WakeOnLan {
    const val DEFAULT_PORT = 9
    const val ALT_PORT = 7
    const val LIMITED_BROADCAST = "255.255.255.255"
    /** How long "Waking…" waits for the NAS before giving up (boot plus middleware start). */
    const val WAKE_TIMEOUT_MS = 150_000L
    const val POLL_INTERVAL_MS = 5_000L

    private val MAC_SEP = Regex("[:\\-. ]")

    /** "aa:bb:cc:dd:ee:ff", "AA-BB-…", "aabb.ccdd.eeff" or 12 hex digits → 6 bytes; null if it isn't a usable unicast MAC. */
    fun parseMac(text: String?): ByteArray? {
        val hex = text?.trim()?.replace(MAC_SEP, "") ?: return null
        if (hex.length != 12 || !hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        val b = ByteArray(6) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        if (b.all { it == 0.toByte() } || b.all { it == 0xFF.toByte() }) return null
        if ((b[0].toInt() and 1) == 1) return null // multicast bit: never a NIC address
        return b
    }

    fun formatMac(b: ByteArray): String = b.joinToString(":") { "%02x".format(it) }

    fun normalizeMac(text: String?): String? = parseMac(text)?.let(::formatMac)

    /** The magic packet: 6 × 0xFF then the MAC 16 times (102 bytes). */
    fun magicPacket(mac: ByteArray): ByteArray {
        require(mac.size == 6)
        return ByteArray(102) { i -> if (i < 6) 0xFF.toByte() else mac[(i - 6) % 6] }
    }

    private fun ipv4(text: String?): ByteArray? {
        val parts = text?.trim()?.split('.') ?: return null
        if (parts.size != 4) return null
        return ByteArray(4) { i -> (parts[i].toIntOrNull()?.takeIf { it in 0..255 } ?: return null).toByte() }
    }

    fun isIpv4(text: String?): Boolean = ipv4(text) != null

    /** Subnet broadcast of "192.168.1.20/24" → "192.168.1.255"; null for IPv6, /31, /32 or bad input. */
    fun subnetBroadcast(cidr: String): String? {
        val addr = ipv4(cidr.substringBefore('/')) ?: return null
        val prefix = cidr.substringAfter('/', "").toIntOrNull() ?: return null
        if (prefix !in 1..30) return null
        val ip = addr.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
        val mask = (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        val bc = ip or (mask.inv() and 0xFFFFFFFFL)
        return (3 downTo 0).joinToString(".") { ((bc shr (it * 8)) and 0xFF).toString() }
    }

    /** One interface the user can pick: its MAC and the subnet broadcast of its first IPv4 address. */
    data class Candidate(val interfaceName: String, val mac: String, val broadcast: String?, val address: String?, val prefix: Int? = null)

    /** Interfaces that can wake the NAS (a real MAC; physical ones first, as [NetInterface] lists are already sorted). */
    fun candidates(interfaces: List<NetInterface>): List<Candidate> = interfaces.filter { !it.notPresent }.mapNotNull { i ->
        val mac = normalizeMac(i.mac) ?: return@mapNotNull null
        val v4 = (i.currentAddresses + i.configuredAddresses).firstOrNull { isIpv4(it.substringBefore('/')) }
        Candidate(i.name, mac, v4?.let(::subnetBroadcast), v4?.substringBefore('/'), v4?.substringAfter('/', "")?.toIntOrNull())
    }.distinctBy { it.interfaceName }

    /** Best default: the interface whose subnet holds the server's address, else the first with an address, else the first. */
    fun bestCandidate(candidates: List<Candidate>, serverHosts: List<String>): Candidate? {
        val hosts = serverHosts.mapNotNull { h -> ipv4(h) }
        fun bits(b: ByteArray) = b.fold(0L) { acc, x -> (acc shl 8) or (x.toLong() and 0xFF) }
        fun sameNet(c: Candidate): Boolean {
            val a = ipv4(c.address) ?: return false
            val prefix = c.prefix?.takeIf { it in 1..32 } ?: return false
            val mask = (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
            return hosts.any { h -> (bits(a) and mask) == (bits(h) and mask) }
        }
        return candidates.firstOrNull(::sameNet) ?: candidates.firstOrNull { it.address != null } ?: candidates.firstOrNull()
    }

    /** "host:port" or "host" (port = [defaultPort]); IPv6 as "[::1]:9". Null for bad input. */
    fun parseRelay(text: String?, defaultPort: Int): Pair<String, Int>? {
        val t = text?.trim().orEmpty()
        if (t.isEmpty() || t.length > 253 || t.any { it.isWhitespace() || it == '/' }) return null
        val (host, port) = when {
            t.startsWith("[") -> t.substringAfter('[').substringBefore(']') to t.substringAfter("]", "").removePrefix(":").ifEmpty { null }
            t.count { it == ':' } == 1 -> t.substringBefore(':') to t.substringAfter(':')
            t.contains(':') -> return null
            else -> t to null
        }
        if (host.isBlank()) return null
        val p = port?.let { it.toIntOrNull() ?: return null } ?: defaultPort
        if (p !in 1..65535) return null
        return host to p
    }

    /** Every place the packet goes, without duplicates: subnet broadcast, 255.255.255.255, then the relay. */
    fun targets(c: WolConfig): List<Pair<String, Int>> = buildList {
        c.broadcast?.trim()?.takeIf { isIpv4(it) }?.let { add(it to c.port) }
        add(LIMITED_BROADCAST to c.port)
        parseRelay(c.relay, c.port)?.let { add(it) }
    }.distinct()

    /** What's wrong with a config before saving (null = fine). */
    fun problem(c: WolConfig): String? = when {
        parseMac(c.mac) == null -> "Enter the NAS's MAC address, like 3c:ec:ef:12:34:56."
        c.port !in 1..65535 -> "Pick port 9 or 7."
        !c.broadcast.isNullOrBlank() && !isIpv4(c.broadcast) -> "The broadcast address must look like 192.168.1.255."
        !c.relay.isNullOrBlank() && parseRelay(c.relay, c.port) == null -> "The relay must look like host:port, e.g. router.lan:9."
        else -> null
    }

    /**
     * Sends the magic packet to every target (3 rounds, the usual practice for UDP). Returns how many sends worked.
     * Needs only the INTERNET permission. Runs on IO.
     */
    suspend fun send(c: WolConfig, rounds: Int = 3): Int = withContext(Dispatchers.IO) {
        val mac = parseMac(c.mac) ?: throw IllegalArgumentException("No valid MAC address")
        val packet = magicPacket(mac)
        var ok = 0
        DatagramSocket().use { socket ->
            socket.broadcast = true
            repeat(rounds) { round ->
                for ((host, port) in targets(c)) {
                    runCatching {
                        socket.send(DatagramPacket(packet, packet.size, InetAddress.getByName(host), port))
                        ok++
                    }
                }
                if (round < rounds - 1) Thread.sleep(100)
            }
        }
        if (ok == 0) throw java.io.IOException("The phone couldn't send the wake-up packet. Is Wi-Fi on?")
        ok
    }

    /** Host and port of each saved address, local first (where a woken NAS answers first). */
    fun probeTargets(s: ServerConfig): List<Pair<String, Int>> = listOfNotNull(s.localUrl, s.tailscaleUrl, s.url).mapNotNull { u ->
        runCatching {
            val uri = java.net.URI(u.trim())
            val host = uri.host ?: return@mapNotNull null
            host to (if (uri.port > 0) uri.port else if (uri.scheme.equals("http", true)) 80 else 443)
        }.getOrNull()
    }.distinct()

    /** Hosts of the saved addresses (for picking the interface on the same subnet). */
    fun hosts(s: ServerConfig): List<String> = probeTargets(s).map { it.first }

    /** True when any saved address accepts a TCP connection (no TLS, no sign-in: just "is it up"). */
    suspend fun reachable(s: ServerConfig, timeoutMs: Int = 1_500): Boolean = withContext(Dispatchers.IO) {
        probeTargets(s).any { (h, p) ->
            runCatching { Socket().use { it.connect(InetSocketAddress(h, p), timeoutMs); true } }.getOrDefault(false)
        }
    }
}
