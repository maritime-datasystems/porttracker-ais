package com.porttracker.ais

/**
 * Minimal AIVDM/AIVDO decoder — just enough fields to re-encode the common
 * message types as NMEA 2000 PGNs. Not a general-purpose AIS library.
 *
 * Handles multi-fragment sentences (types 5 and 24 span two fragments).
 * Thread-safety: call [decode] from ONE thread only (the NmeaOutputServer
 * dispatcher); the fragment-assembly buffer is not synchronized.
 */
object AisDecoder {

    /** Decoded AIS message; only fields needed for N2K conversion. */
    class AisMessage {
        var type = 0
        var repeat = 0
        var mmsi = 0L
        // Position reports (1/2/3/18/19)
        var navStatus = 15          // 15 = undefined
        var rotAis = -128           // raw AIS ROT (-127..127, -128 = n/a)
        var sogTenths = 1023        // 0.1 kn, 1023 = n/a
        var posAccuracy = 0
        var lon = 181.0             // degrees, 181 = n/a
        var lat = 91.0              // degrees, 91 = n/a
        var cogTenths = 3600        // 0.1 deg, 3600 = n/a
        var heading = 511           // deg, 511 = n/a
        var timestamp = 60
        var maneuver = 0
        var raim = 0
        var radio = 0
        // Static data (5/19/24)
        var imo = 0L
        var callsign = ""
        var name = ""
        var shipType = 0
        var dimBow = 0; var dimStern = 0; var dimPort = 0; var dimStarboard = 0
        var etaMonth = 0; var etaDay = 0; var etaHour = 24; var etaMinute = 60
        var draughtTenths = 0       // 0.1 m
        var destination = ""
        var aisVersion = 0
        var partNo = 0              // type 24
        var vendorId = ""           // type 24B
        var mothershipMmsi = 0L     // type 24B
    }

    private class BitReader(private val bits: ByteArray) {
        // bits[] holds one 6-bit value per payload character
        var pos = 0
        val size = bits.size * 6

        fun uint(n: Int): Long {
            var v = 0L
            var remaining = n
            while (remaining > 0 && pos < size) {
                val ch = bits[pos / 6].toInt()
                val bitInCh = 5 - (pos % 6)
                v = (v shl 1) or ((ch shr bitInCh) and 1).toLong()
                pos++
                remaining--
            }
            // Ran off the end: pad with zeros like most decoders do
            if (remaining > 0) v = v shl remaining
            return v
        }

        fun int(n: Int): Long {
            var v = uint(n)
            if (v and (1L shl (n - 1)) != 0L) v = v - (1L shl n)
            return v
        }

        fun string(n: Int): String {
            val sb = StringBuilder()
            var remaining = n
            while (remaining >= 6) {
                val c = uint(6).toInt()
                if (c == 0) { uint(remaining - 6); break }   // '@' terminates
                sb.append(if (c < 32) (c + 64).toChar() else c.toChar())
                remaining -= 6
            }
            return sb.toString().trim()
        }
    }

    // Fragment assembly: key = "seqId-channel", value = (expected, received so far)
    private class Pending(val total: Int) {
        val parts = arrayOfNulls<String>(total)
        var fillBits = 0
        val createdAt = System.currentTimeMillis()
    }
    private val pending = HashMap<String, Pending>()

    /**
     * Feed one raw NMEA line (tag block allowed). Returns the decoded message
     * once complete, or null (unsupported type, parse error, or waiting for
     * more fragments).
     */
    fun decode(rawLine: String): AisMessage? {
        val line = stripTagBlock(rawLine)
        if (!line.startsWith("!AIVDM") && !line.startsWith("!AIVDO")) return null
        val star = line.indexOf('*')
        val body = if (star > 0) line.substring(0, star) else line
        val f = body.split(',')
        if (f.size < 7) return null
        val total = f[1].toIntOrNull() ?: return null
        val num = f[2].toIntOrNull() ?: return null
        val seqId = f[3]
        val channel = f[4]
        val payload = f[5]
        val fill = f[6].toIntOrNull() ?: 0

        val fullPayload: String
        val fullFill: Int
        if (total <= 1) {
            fullPayload = payload
            fullFill = fill
        } else {
            val key = "$seqId-$channel"
            val p = pending.getOrPut(key) { Pending(total) }
            if (num in 1..p.total) p.parts[num - 1] = payload
            if (num == total) p.fillBits = fill
            if (p.parts.any { it == null }) {
                cleanupPending()
                return null
            }
            pending.remove(key)
            fullPayload = p.parts.joinToString("")
            fullFill = p.fillBits
        }
        return try {
            decodePayload(fullPayload, fullFill)
        } catch (e: Exception) {
            null
        }
    }

    /** Tag blocks look like: \s:station,c:12345*XX\!AIVDM,... */
    fun stripTagBlock(line: String): String {
        var s = line.trim()
        if (s.startsWith("\\")) {
            val end = s.indexOf('\\', 1)
            if (end > 0) s = s.substring(end + 1)
        }
        return s
    }

    private fun cleanupPending() {
        if (pending.size < 32) return
        val cutoff = System.currentTimeMillis() - 30_000
        pending.entries.removeAll { it.value.createdAt < cutoff }
    }

    private fun decodePayload(payload: String, @Suppress("UNUSED_PARAMETER") fillBits: Int): AisMessage? {
        val bits = ByteArray(payload.length)
        for (i in payload.indices) {
            var c = payload[i].code - 48
            if (c > 40) c -= 8
            if (c < 0 || c > 63) return null
            bits[i] = c.toByte()
        }
        val r = BitReader(bits)
        val m = AisMessage()
        m.type = r.uint(6).toInt()
        m.repeat = r.uint(2).toInt()
        m.mmsi = r.uint(30)

        when (m.type) {
            1, 2, 3 -> {
                m.navStatus = r.uint(4).toInt()
                m.rotAis = r.int(8).toInt()
                m.sogTenths = r.uint(10).toInt()
                m.posAccuracy = r.uint(1).toInt()
                m.lon = r.int(28) / 600000.0
                m.lat = r.int(27) / 600000.0
                m.cogTenths = r.uint(12).toInt()
                m.heading = r.uint(9).toInt()
                m.timestamp = r.uint(6).toInt()
                m.maneuver = r.uint(2).toInt()
                r.uint(3)                       // spare
                m.raim = r.uint(1).toInt()
                m.radio = r.uint(19).toInt()
            }
            18 -> {
                r.uint(8)                       // regional reserved
                m.sogTenths = r.uint(10).toInt()
                m.posAccuracy = r.uint(1).toInt()
                m.lon = r.int(28) / 600000.0
                m.lat = r.int(27) / 600000.0
                m.cogTenths = r.uint(12).toInt()
                m.heading = r.uint(9).toInt()
                m.timestamp = r.uint(6).toInt()
                r.uint(2)                       // regional
                r.uint(4)                       // CS/display/DSC/band flags (partial)
                r.uint(2)                       // msg22 + assigned
                m.raim = r.uint(1).toInt()
                m.radio = r.uint(20).toInt()
            }
            19 -> {
                r.uint(8)
                m.sogTenths = r.uint(10).toInt()
                m.posAccuracy = r.uint(1).toInt()
                m.lon = r.int(28) / 600000.0
                m.lat = r.int(27) / 600000.0
                m.cogTenths = r.uint(12).toInt()
                m.heading = r.uint(9).toInt()
                m.timestamp = r.uint(6).toInt()
                r.uint(4)                       // regional
                m.name = r.string(120)
                m.shipType = r.uint(8).toInt()
                m.dimBow = r.uint(9).toInt()
                m.dimStern = r.uint(9).toInt()
                m.dimPort = r.uint(6).toInt()
                m.dimStarboard = r.uint(6).toInt()
            }
            5 -> {
                m.aisVersion = r.uint(2).toInt()
                m.imo = r.uint(30)
                m.callsign = r.string(42)
                m.name = r.string(120)
                m.shipType = r.uint(8).toInt()
                m.dimBow = r.uint(9).toInt()
                m.dimStern = r.uint(9).toInt()
                m.dimPort = r.uint(6).toInt()
                m.dimStarboard = r.uint(6).toInt()
                r.uint(4)                       // EPFD type
                m.etaMonth = r.uint(4).toInt()
                m.etaDay = r.uint(5).toInt()
                m.etaHour = r.uint(5).toInt()
                m.etaMinute = r.uint(6).toInt()
                m.draughtTenths = r.uint(8).toInt()
                m.destination = r.string(120)
            }
            24 -> {
                m.partNo = r.uint(2).toInt()
                if (m.partNo == 0) {
                    m.name = r.string(120)
                } else {
                    m.shipType = r.uint(8).toInt()
                    m.vendorId = r.string(42)
                    m.callsign = r.string(42)
                    // For auxiliary craft (MMSI 98xxxxxxx) these 30 bits are
                    // the mothership MMSI instead of dimensions.
                    if (m.mmsi / 10_000_000L == 98L) {
                        m.mothershipMmsi = r.uint(30)
                    } else {
                        m.dimBow = r.uint(9).toInt()
                        m.dimStern = r.uint(9).toInt()
                        m.dimPort = r.uint(6).toInt()
                        m.dimStarboard = r.uint(6).toInt()
                    }
                }
            }
            else -> return null                 // unsupported for N2K conversion
        }
        return m
    }
}
