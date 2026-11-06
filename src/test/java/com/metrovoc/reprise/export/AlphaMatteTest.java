package com.metrovoc.reprise.export;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AlphaMatteTest {
    @Test void restoresCoverageAndCompositesOverAnyBackground() {
        int[] color = {200, 16, 90};
        for (int coverage = 0; coverage <= 255; coverage++) {
            byte[] black = new byte[4], white = new byte[4];
            for (int c = 0; c < 3; c++) {
                int premultiplied = Math.round(color[c] * coverage / 255f);
                black[c] = (byte) premultiplied;
                white[c] = (byte) (premultiplied + 255 - coverage);
            }
            AlphaMatte.combine(black, white);
            assertEquals(coverage, black[3] & 255);
            for (int c = 0; c < 3; c++) {
                int background = 48 + c * 72;
                float expected = (color[c] * coverage + background * (255 - coverage)) / 255f;
                float composite = ((black[c] & 255) * coverage + background * (255 - coverage)) / 255f;
                assertEquals(expected, composite, 1f);
            }
        }
    }
}
