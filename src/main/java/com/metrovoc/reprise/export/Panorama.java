package com.metrovoc.reprise.export;

import java.util.EnumSet;
import java.util.List;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Projects six square 90-degree views to a bottom-up equirectangular frame. */
public final class Panorama {
    // Capture front last so the audio mix uses the shot's forward direction.
    public enum Face { BACK, LEFT, RIGHT, TOP, BOTTOM, FRONT }

    final List<Face> captureFaces;
    private final int size;
    private final int[] samples;
    private final float[] radialScale;

    Panorama(int width, int height, int size, boolean depth) {
        var used = EnumSet.noneOf(Face.class);
        this.size = size;
        samples = new int[Math.multiplyExact(width, height)];
        radialScale = depth ? new float[samples.length] : null;
        double[] sin = new double[width], cos = new double[width];
        for (int x = 0; x < width; x++) {
            double longitude = (x + 0.5 - width * 0.5) * Math.PI / (2 * size);
            sin[x] = Math.sin(longitude);
            cos[x] = Math.cos(longitude);
        }
        for (int y = 0; y < height; y++) {
            double latitude = (y + 0.5 - height * 0.5) * Math.PI / (2 * size);
            double up = Math.sin(latitude), horizontal = Math.cos(latitude);
            for (int x = 0; x < width; x++) {
                double right = sin[x] * horizontal, front = cos[x] * horizontal;
                double major = Math.max(Math.abs(front), Math.max(Math.abs(right), Math.abs(up)));
                Face face;
                double u, v;
                if (major == Math.abs(up)) {
                    face = up > 0 ? Face.TOP : Face.BOTTOM;
                    u = right;
                    v = up > 0 ? -front : front;
                } else if (major == Math.abs(right)) {
                    face = right > 0 ? Face.RIGHT : Face.LEFT;
                    u = right > 0 ? -front : front;
                    v = up;
                } else {
                    face = front > 0 ? Face.FRONT : Face.BACK;
                    u = front > 0 ? right : -right;
                    v = up;
                }
                used.add(face);
                int index = y * width + x;
                samples[index] = (face.ordinal() * size + texel(v / major)) * size + texel(u / major);
                if (depth) radialScale[index] = (float) (1 / major);
            }
        }
        captureFaces = List.copyOf(used);
    }

    private int texel(double coordinate) {
        return Math.clamp((int) ((coordinate + 1) * 0.5 * size), 0, size - 1);
    }

    byte[] project(byte[][] faces) {
        int area = size * size;
        byte[] result = new byte[Math.multiplyExact(samples.length, 4)];
        for (int i = 0; i < samples.length; i++) {
            int sample = samples[i];
            System.arraycopy(faces[sample / area], (sample % area) * 4, result, i * 4, 4);
        }
        return result;
    }

    /** Pack bottom-up GPU rows into a horizontal cube cross without resampling. */
    static byte[] cross(byte[][] faces, int size, boolean depth) {
        int stride = size * 4;
        byte[] result = new byte[Math.multiplyExact(faces[0].length, 12)];
        if (depth) {
            // Empty tiles have the same far-plane value as sky in the packed depth texture.
            var pixels = java.nio.ByteBuffer.wrap(result).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
            while (pixels.hasRemaining()) pixels.put(1.0f);
        }
        for (Face face : Face.values()) {
            int column = switch (face) {
                case LEFT -> 0;
                case RIGHT -> 2;
                case BACK -> 3;
                default -> 1;
            };
            int row = switch (face) {
                case BOTTOM -> 0;
                case TOP -> 2;
                default -> 1;
            };
            for (int y = 0; y < size; y++) {
                System.arraycopy(faces[face.ordinal()], y * stride, result,
                    ((row * size + y) * 4 + column) * stride, stride);
            }
        }
        return result;
    }

    float[] radialScale() { return radialScale; }

    /** Camera.setRotation uses YXZ Euler angles. Apply the face offset in the shot's local coordinates. */
    public static Vector3f angles(Face face, float yaw, float pitch, float roll) {
        float baseY = (float) Math.toRadians(180 - yaw);
        var q = new Quaternionf().rotationYXZ(baseY, (float) Math.toRadians(-pitch), (float) Math.toRadians(-roll));
        float quarter = (float) (Math.PI / 2);
        switch (face) {
            case BACK -> q.rotateY(2 * quarter);
            case LEFT -> q.rotateY(quarter);
            case RIGHT -> q.rotateY(-quarter);
            case TOP -> q.rotateX(quarter);
            case BOTTOM -> q.rotateX(-quarter);
            case FRONT -> { return new Vector3f(yaw, pitch, roll); }
        }
        return cameraAngles(q, baseY);
    }

    /** Convert a composed camera rotation back to Minecraft angles, retaining yaw at a pole. */
    public static Vector3f cameraAngles(Quaternionf q, float baseY) {
        var back = q.transformUnitPositiveZ(new Vector3f());
        double horizontal = Math.hypot(back.x, back.z);
        float y = horizontal < 1e-6 ? baseY : (float) Math.atan2(back.x, back.z);
        float x = (float) Math.atan2(-back.y, horizontal);
        var zRotation = new Quaternionf().rotationYXZ(y, x, 0).conjugate().mul(q);
        return new Vector3f(180 - (float) Math.toDegrees(y), -(float) Math.toDegrees(x),
            -(float) Math.toDegrees(2 * Math.atan2(zRotation.z, zRotation.w)));
    }
}
