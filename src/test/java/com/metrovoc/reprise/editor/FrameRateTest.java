package com.metrovoc.reprise.editor;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FrameRateTest {
    @Test void exactDecimalAndBroadcastRatesStayDistinct() {
        assertEquals(new FrameRate(2997, 100), FrameRate.parse("29.970"));
        assertEquals("30000/1001", FrameRate.parse("60000/2002").toString());
        assertEquals("25", FrameRate.parse("25.0").toString());
        for (String value : new String[]{"1/0", "0", "NaN", "48001", "10/-2"}) {
            assertThrows(IllegalArgumentException.class, () -> FrameRate.parse(value));
        }
    }
}
