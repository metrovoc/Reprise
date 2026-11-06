package com.metrovoc.reprise.editor;

import java.math.BigDecimal;
import java.math.BigInteger;

/** Exact video rate, shared by the encoder and the audio sample clock. */
public record FrameRate(int numerator, int denominator) {
    public FrameRate {
        if (denominator <= 0 || numerator < denominator || numerator > 48000L * denominator) {
            throw new IllegalArgumentException("Frame rate must be between 1 and 48000 fps");
        }
        int gcd = BigInteger.valueOf(numerator).gcd(BigInteger.valueOf(denominator)).intValueExact();
        numerator /= gcd;
        denominator /= gcd;
    }

    public static FrameRate parse(String text) {
        String value = text.trim();
        if (value.matches("[0-9]+/[0-9]+")) {
            String[] parts = value.split("/");
            return new FrameRate(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
        }
        if (!value.matches("[0-9]+(\\.[0-9]{1,6})?")) throw new NumberFormatException();
        BigDecimal number = new BigDecimal(value);
        BigInteger num = number.unscaledValue(), den = BigInteger.TEN.pow(number.scale());
        BigInteger gcd = num.gcd(den);
        return new FrameRate(num.divide(gcd).intValueExact(), den.divide(gcd).intValueExact());
    }

    public double value() { return (double) numerator / denominator; }

    @Override public String toString() { return denominator == 1 ? Integer.toString(numerator) : numerator + "/" + denominator; }
}
