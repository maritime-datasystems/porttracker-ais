package com.porttracker.ais

import org.junit.Assert.*
import org.junit.Test

class NmeaSentencesTest {
    @Test fun nativeBatchIsDeliveredAsIndividualSentences() {
        val first = "!AIVDM,1,1,,A,15Muq?P0000G?t@E>4p@0?vN0<0u,0*00"
        val second = "!AIVDM,1,1,,B,15Muq?P0000G?t@E>4p@0?vN0<0u,0*00"
        assertEquals(listOf(first, second), nmeaSentences("\n$first\r\n$second\n").toList())
    }
    @Test fun blankBatchDoesNotCountAsRadioTraffic() {
        assertTrue(nmeaSentences(" \r\n\n").none())
    }
    @Test fun tagBlocksAndMultipartBoundariesArePreserved() {
        val first = "\\s:station*00\\!AIVDM,2,1,1,A,abc,0*00"
        val second = "!AIVDM,2,2,1,A,def,2*00"
        assertEquals(listOf(first, second), nmeaSentences("$first\n$second").toList())
    }
}
