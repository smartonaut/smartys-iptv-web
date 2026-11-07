package com.smartys.iptv

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit-Tests für die Player-Identität nach PLAYER-IDENTITY.md (v1).
 * derivePlayerId ist eine reine Funktion ohne Android-Bezug und lässt sich
 * daher direkt auf der JVM testen. Der Testvektor der Spezifikation muss
 * auf JEDER Plattform exakt herauskommen.
 */
class PlayerIdentityTest {

    /**
     * Testvektor für die Ableitung — bewusst mit FIKTIVEN Werten.
     *
     * Früher stand hier eine echte SF8008: Seriennummer und Geräte-MAC eines
     * realen Receivers. Das Repo ist öffentlich, und eine Kombination aus
     * Seriennummer und MAC ist ein Geräte-Fingerabdruck — die gehört nicht
     * ins Netz. Der Test prüft die Ableitung genauso gut mit Nullen und einer
     * lokal administrierten MAC (02:...).
     */
    private val specSeed =
        "boxtype=sf8008|sn=000000000000|chipset=3798mv200|board_revision=1.0|nic=02:00:00:00:00:01"

    @Test
    fun specTestVector() {
        assertEquals("AA:20:D7:E8:8C:12", PlayerIdentity.derivePlayerId(specSeed))
    }

    @Test
    fun deterministicMacFormat() {
        val a = PlayerIdentity.derivePlayerId("android_id=abc123|manufacturer=Amazon|model=AFTMM")
        val b = PlayerIdentity.derivePlayerId("android_id=abc123|manufacturer=Amazon|model=AFTMM")
        assertEquals(a, b)
        assertEquals(17, a.length)                        // 6 Bytes -> 6*2 + 5 Doppelpunkte
        assertEquals(6, a.split(":").size)               // MAC-Format XX:XX:XX:XX:XX:XX
        assertEquals(a, a.uppercase())                     // immer UPPERCASE
    }

    @Test
    fun locallyAdministeredUnicast() {
        // Byte 0: (b & 0xFC) | 0x02 -> lokal administriert (Bit 1) und
        // Unicast (Bit 0 = 0): keine Kollision mit echten Hardware-MACs.
        val firstByte = PlayerIdentity.derivePlayerId(specSeed)
            .substringBefore(":").toInt(16)
        assertEquals(0x02, firstByte and 0x03)
    }
}