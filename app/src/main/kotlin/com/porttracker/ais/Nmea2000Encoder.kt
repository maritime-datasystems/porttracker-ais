package com.porttracker.ais

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * Encodes decoded AIS messages and own-ship GPS fixes as NMEA 2000 PGNs and
 * frames them for the two common NMEA-2000-over-IP text formats:
 *
 *  - Yacht Devices RAW ("YDWG" format, understood by OpenCPN's
 *    "Yacht Devices RAW" network connection and many apps):
 *      hh:mm:ss.mmm R <8-hex CAN id> <up to 8 hex bytes>
 *    PGNs longer than 8 bytes use standard fast-packet framing.
 *
 *  - SeaSmart.Net ($PCDIN NMEA-0183 wrapper, one sentence per PGN):
 *      $PCDIN,<PGN 6 hex>,<timestamp 8 hex>,<src 2 hex>,<data hex>*CS
 *
 * Field layouts follow the canboat PGN definitions. This is an emulation of
 * an AIS receiver on an N2K network — good enough for chartplotter apps, not
 * a certified gateway.
 */
object Nmea2000Encoder {

    const val FORMAT_YDRAW = "n2k-ydraw"
    const val FORMAT_PCDIN = "n2k-pcdin"

    private const val SRC = 43            // our emulated device address
    private const val PRIO = 4
    private var fastPacketSeq = 0

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /** Encode one decoded AIS message; returns output lines (possibly several). */
    fun encodeAis(m: AisDecoder.AisMessage, format: String): List<String> {
        val payload: Pair<Int, ByteArray> = when (m.type) {
            1, 2, 3 -> 129038 to classAPosition(m)
            18 -> 129039 to classBPosition(m)
            19 -> 129039 to classBPosition(m)     // position part of extended class B
            5 -> 129794 to classAStatic(m)
            24 -> if (m.partNo == 0) 129809 to classBStaticA(m)
                  else 129810 to classBStaticB(m)
            else -> return emptyList()
        }
        return frame(payload.first, payload.second, format)
    }

    /** Own-ship GPS fix → 129025 (position) + 129026 (COG/SOG) [+ 127250 heading]. */
    fun encodeGps(
        lat: Double, lon: Double, sogKn: Double, cogDeg: Double,
        headingDeg: Float?, format: String,
    ): List<String> {
        val out = ArrayList<String>(3)
        // 129025 — Position, Rapid Update
        val p = buf(8)
        p.putInt((lat * 1e7).toInt())
        p.putInt((lon * 1e7).toInt())
        out += frame(129025, p.array(), format)
        // 129026 — COG & SOG, Rapid Update
        val c = buf(8)
        c.put(0xFF.toByte())                              // SID
        c.put(0xFC.toByte())                              // COG ref: true (0) + reserved
        c.putShort(radians1e4(cogDeg))
        c.putShort(((sogKn * 0.514444) / 0.01).toInt().toShort())
        c.putShort(0xFFFF.toShort())                      // reserved
        out += frame(129026, c.array(), format)
        // 127250 — Vessel Heading (only when we actually have one)
        if (headingDeg != null) {
            val h = buf(8)
            h.put(0xFF.toByte())
            h.putShort(radians1e4(headingDeg.toDouble()))
            h.putShort(0x7FFF.toShort())                  // deviation n/a
            h.putShort(0x7FFF.toShort())                  // variation n/a
            h.put(0xFD.toByte())                          // ref: magnetic? -> 1=magnetic, 0=true; use true(0)|reserved
            out += frame(127250, h.array(), format)
        }
        return out
    }

    // ------------------------------------------------------------------
    // PGN payloads (canboat layouts, little-endian)
    // ------------------------------------------------------------------

    private fun buf(n: Int): ByteBuffer =
        ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)

    private fun radians1e4(deg: Double): Short {
        if (deg < 0 || deg >= 360) return 0xFFFF.toShort()
        return ((Math.toRadians(deg)) / 1e-4).toInt().toShort()
    }

    /** PGN 129038 — Class A Position Report (28 bytes). */
    private fun classAPosition(m: AisDecoder.AisMessage): ByteArray {
        val b = buf(28)
        b.put(((m.repeat shl 6) or m.type).toByte())
        b.putInt(m.mmsi.toInt())
        b.putInt(if (m.lon > 180) 0x7FFFFFFF else (m.lon * 1e7).toInt())
        b.putInt(if (m.lat > 90) 0x7FFFFFFF else (m.lat * 1e7).toInt())
        b.put((m.posAccuracy or (m.raim shl 1) or ((m.timestamp and 0x3F) shl 2)).toByte())
        b.putShort(cog(m))
        b.putShort(sog(m))
        // Communication state (19 bits) + AIS transceiver info (5 bits): channel A VDL
        val comm = (m.radio and 0x7FFFF) or (0 shl 19)
        b.put((comm and 0xFF).toByte())
        b.put(((comm shr 8) and 0xFF).toByte())
        b.put(((comm shr 16) and 0xFF).toByte())
        b.putShort(heading(m))
        b.putShort(rot(m))
        b.put(((m.navStatus and 0x0F) or ((m.maneuver and 0x03) shl 4) or 0xC0).toByte())
        b.put(0xFF.toByte())                              // spare + reserved
        b.put(0xFF.toByte())                              // sequence ID n/a
        return b.array()
    }

    /** PGN 129039 — Class B Position Report (26 bytes). */
    private fun classBPosition(m: AisDecoder.AisMessage): ByteArray {
        val b = buf(26)
        b.put(((m.repeat shl 6) or m.type).toByte())
        b.putInt(m.mmsi.toInt())
        b.putInt(if (m.lon > 180) 0x7FFFFFFF else (m.lon * 1e7).toInt())
        b.putInt(if (m.lat > 90) 0x7FFFFFFF else (m.lat * 1e7).toInt())
        b.put((m.posAccuracy or (m.raim shl 1) or ((m.timestamp and 0x3F) shl 2)).toByte())
        b.putShort(cog(m))
        b.putShort(sog(m))
        val comm = (m.radio and 0x7FFFF) or (0 shl 19)
        b.put((comm and 0xFF).toByte())
        b.put(((comm shr 8) and 0xFF).toByte())
        b.put(((comm shr 16) and 0xFF).toByte())
        b.putShort(heading(m))
        b.put(0x00)                                       // regional application
        b.put(0x3E)                                       // CS unit + flags (SOTDMA=0, defaults)
        b.put(0xFF.toByte())                              // assigned/state + reserved
        return b.array()
    }

    /** PGN 129794 — Class A Static and Voyage Data (75 bytes). */
    private fun classAStatic(m: AisDecoder.AisMessage): ByteArray {
        val b = buf(75)
        b.put(((m.repeat shl 6) or m.type).toByte())
        b.putInt(m.mmsi.toInt())
        b.putInt(m.imo.toInt())
        putAscii(b, m.callsign, 7)
        putAscii(b, m.name, 20)
        b.put(m.shipType.toByte())
        val lenTenths = (m.dimBow + m.dimStern) * 10
        val beamTenths = (m.dimPort + m.dimStarboard) * 10
        b.putShort(if (lenTenths > 0) lenTenths.toShort() else 0xFFFF.toShort())
        b.putShort(if (beamTenths > 0) beamTenths.toShort() else 0xFFFF.toShort())
        b.putShort(if (beamTenths > 0) (m.dimStarboard * 10).toShort() else 0xFFFF.toShort())
        b.putShort(if (lenTenths > 0) (m.dimBow * 10).toShort() else 0xFFFF.toShort())
        b.putShort(etaDate(m))
        b.putInt(etaTime(m))
        b.putShort(if (m.draughtTenths > 0) (m.draughtTenths * 10).toShort() else 0xFFFF.toShort())
        putAscii(b, m.destination, 20)
        // AIS version (2) + GNSS type (4) + DTE (1) + reserved (1)
        b.put(((m.aisVersion and 0x03) or (0 shl 2) or (0 shl 6)).toByte())
        b.put(0xE0.toByte())                              // transceiver info + reserved
        return b.array()
    }

    /** PGN 129809 — Class B Static Data, Part A (27 bytes). */
    private fun classBStaticA(m: AisDecoder.AisMessage): ByteArray {
        val b = buf(27)
        b.put(((m.repeat shl 6) or m.type).toByte())
        b.putInt(m.mmsi.toInt())
        putAscii(b, m.name, 20)
        b.put(0xE0.toByte())                              // transceiver + reserved
        b.put(0xFF.toByte())                              // sequence ID
        return b.array()
    }

    /** PGN 129810 — Class B Static Data, Part B (34 bytes). */
    private fun classBStaticB(m: AisDecoder.AisMessage): ByteArray {
        val b = buf(34)
        b.put(((m.repeat shl 6) or m.type).toByte())
        b.putInt(m.mmsi.toInt())
        b.put(m.shipType.toByte())
        putAscii(b, m.vendorId, 7)
        putAscii(b, m.callsign, 7)
        val lenTenths = (m.dimBow + m.dimStern) * 10
        val beamTenths = (m.dimPort + m.dimStarboard) * 10
        b.putShort(if (lenTenths > 0) lenTenths.toShort() else 0xFFFF.toShort())
        b.putShort(if (beamTenths > 0) beamTenths.toShort() else 0xFFFF.toShort())
        b.putShort(if (beamTenths > 0) (m.dimStarboard * 10).toShort() else 0xFFFF.toShort())
        b.putShort(if (lenTenths > 0) (m.dimBow * 10).toShort() else 0xFFFF.toShort())
        b.putInt(if (m.mothershipMmsi > 0) m.mothershipMmsi.toInt() else -1)
        b.put(0xFF.toByte())                              // reserved
        b.put(0xE0.toByte())                              // transceiver + reserved
        return b.array()
    }

    private fun cog(m: AisDecoder.AisMessage): Short =
        if (m.cogTenths >= 3600) 0xFFFF.toShort()
        else radians1e4(m.cogTenths / 10.0)

    private fun sog(m: AisDecoder.AisMessage): Short =
        if (m.sogTenths >= 1023) 0xFFFF.toShort()
        else (((m.sogTenths / 10.0) * 0.514444) / 0.01).toInt().toShort()

    private fun heading(m: AisDecoder.AisMessage): Short =
        if (m.heading >= 511) 0xFFFF.toShort()
        else radians1e4(m.heading.toDouble())

    private fun rot(m: AisDecoder.AisMessage): Short {
        if (m.rotAis == -128) return 0x7FFF
        // AIS ROT: (v/4.733)^2 deg/min, signed. N2K: 3.125e-5 rad/s units.
        val degPerMin = (m.rotAis / 4.733) * (m.rotAis / 4.733) *
            (if (m.rotAis < 0) -1 else 1)
        val radPerSec = Math.toRadians(degPerMin) / 60.0
        return (radPerSec / 3.125e-5).toInt().coerceIn(-32766, 32766).toShort()
    }

    private fun etaDate(m: AisDecoder.AisMessage): Short {
        if (m.etaMonth == 0 || m.etaDay == 0) return 0xFFFF.toShort()
        val now = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        var year = now.get(java.util.Calendar.YEAR)
        if (m.etaMonth < now.get(java.util.Calendar.MONTH) + 1) year++
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(year, m.etaMonth - 1, m.etaDay)
        return (cal.timeInMillis / 86_400_000L).toShort()
    }

    private fun etaTime(m: AisDecoder.AisMessage): Int {
        if (m.etaHour >= 24) return -1                    // 0xFFFFFFFF
        return (((m.etaHour * 3600) + (m.etaMinute % 60) * 60) / 0.0001).toInt()
    }

    private fun putAscii(b: ByteBuffer, s: String, len: Int) {
        for (i in 0 until len) {
            b.put(if (i < s.length) s[i].code.toByte() else ' '.code.toByte())
        }
    }

    // ------------------------------------------------------------------
    // Framing
    // ------------------------------------------------------------------

    private fun frame(pgn: Int, data: ByteArray, format: String): List<String> =
        when (format) {
            FORMAT_PCDIN -> listOf(pcdin(pgn, data))
            else -> ydraw(pgn, data)
        }

    private fun canId(pgn: Int): Int =
        (PRIO shl 26) or (pgn shl 8) or SRC

    private fun ydrawTime(): String {
        val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        return String.format(
            Locale.US, "%02d:%02d:%02d.%03d",
            c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE),
            c.get(java.util.Calendar.SECOND), c.get(java.util.Calendar.MILLISECOND))
    }

    private fun hex(b: ByteArray, from: Int, to: Int): String {
        val sb = StringBuilder()
        for (i in from until to) {
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(String.format(Locale.US, "%02X", b[i]))
        }
        return sb.toString()
    }

    /** Yacht Devices RAW lines; fast-packet framing for payloads > 8 bytes. */
    private fun ydraw(pgn: Int, data: ByteArray): List<String> {
        val id = String.format(Locale.US, "%08X", canId(pgn))
        val time = ydrawTime()
        if (data.size <= 8) {
            return listOf("$time R $id ${hex(data, 0, data.size)}")
        }
        // Fast packet
        val seq = (fastPacketSeq++ and 0x07) shl 5
        val out = ArrayList<String>()
        var frameNo = 0
        var pos = 0
        while (pos < data.size) {
            val frame = ByteArray(8) { 0xFF.toByte() }
            frame[0] = (seq or frameNo).toByte()
            var idx = 1
            if (frameNo == 0) {
                frame[1] = data.size.toByte()
                idx = 2
            }
            while (idx < 8 && pos < data.size) {
                frame[idx++] = data[pos++]
            }
            out += "$time R $id ${hex(frame, 0, 8)}"
            frameNo++
        }
        return out
    }

    /** SeaSmart.Net wrapper — whole PGN in one $PCDIN sentence. */
    private fun pcdin(pgn: Int, data: ByteArray): String {
        val sb = StringBuilder("\$PCDIN,")
        sb.append(String.format(Locale.US, "%06X", pgn)).append(',')
        sb.append(String.format(Locale.US, "%08X",
            (System.currentTimeMillis() / 10) and 0xFFFFFFFFL)).append(',')
        sb.append(String.format(Locale.US, "%02X", SRC)).append(',')
        for (b in data) sb.append(String.format(Locale.US, "%02X", b))
        var cs = 0
        for (i in 1 until sb.length) cs = cs xor sb[i].code
        sb.append('*').append(String.format(Locale.US, "%02X", cs))
        return sb.toString()
    }
}
