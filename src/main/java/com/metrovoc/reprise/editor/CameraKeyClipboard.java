package com.metrovoc.reprise.editor;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

final class CameraKeyClipboard {
    private static final String PREFIX = "Reprise camera keys v1\n";
    private static final Gson JSON = new Gson();

    static String encode(Collection<Keyframe> selected) {
        var keys = selected.stream().map(Keyframe::copy).sorted(Comparator.comparingDouble(k -> k.tick)).toList();
        double start = keys.getFirst().tick;
        keys.forEach(key -> key.tick -= start);
        return PREFIX + JSON.toJson(keys);
    }

    static List<Keyframe> decode(String text, double cursor, int duration) {
        if (!text.startsWith(PREFIX)) return List.of();
        var keys = new ArrayList<Keyframe>();
        double previous = -1;
        try {
            for (var element : JsonParser.parseString(text.substring(PREFIX.length())).getAsJsonArray()) {
                var object = element.getAsJsonObject();
                for (var field : List.of("tick", "x", "y", "z", "yaw", "pitch", "roll", "fov", "interpolation")) {
                    if (!object.has(field) || object.get(field).isJsonNull()) return List.of();
                }
                var k = JSON.fromJson(object, Keyframe.class);
                if (!Double.isFinite(k.tick) || k.tick < 0 || k.tick <= previous || k.interpolation == null
                    || !Double.isFinite(k.x) || !Double.isFinite(k.y) || !Double.isFinite(k.z)
                    || !Float.isFinite(k.yaw) || !Float.isFinite(k.pitch) || !Float.isFinite(k.roll)
                    || !(k.fov > 0 && k.fov < 180)) return List.of();
                if (k.target != null && (k.target.mode() == null || k.target.point() == null || k.target.name() == null
                    || !Double.isFinite(k.target.x()) || !Double.isFinite(k.target.y()) || !Double.isFinite(k.target.z()))) return List.of();
                if (k.spline != null && (!validOffsets(k.spline.startOffset()) || !validOffsets(k.spline.endOffset()))) return List.of();
                previous = k.tick;
                k.tick += cursor;
                if (k.tick <= duration) keys.add(k);
            }
        } catch (RuntimeException malformed) {
            return List.of();
        }
        return keys;
    }

    private static boolean validOffsets(double[] offsets) {
        return offsets != null && offsets.length == 6 && java.util.Arrays.stream(offsets).allMatch(Double::isFinite);
    }
}
