package com.metrovoc.reprise.export;

/** Recover straight RGBA from the same frame composited on black and on white. */
final class AlphaMatte {
    private AlphaMatte() {}

    static void combine(byte[] black, byte[] white) {
        for (int i = 0; i < black.length; i += 4) {
            int r = black[i] & 255, g = black[i + 1] & 255, b = black[i + 2] & 255;
            int transmitted = ((white[i] & 255) - r + (white[i + 1] & 255) - g + (white[i + 2] & 255) - b + 1) / 3;
            int alpha = Math.min(255, Math.max(255 - transmitted, Math.max(r, Math.max(g, b))));
            black[i + 3] = (byte) alpha;
            if (alpha > 0 && alpha < 255) {
                black[i] = (byte) ((255 * r + alpha / 2) / alpha);
                black[i + 1] = (byte) ((255 * g + alpha / 2) / alpha);
                black[i + 2] = (byte) ((255 * b + alpha / 2) / alpha);
            }
        }
    }
}
