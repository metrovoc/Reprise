package com.metrovoc.reprise.editor;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Calculates a timeline drag from original key times, without changing the project during preview. */
final class CameraKeyDrag {
    final Keyframe grabbed;
    final Map<Keyframe, Double> preview = new HashMap<>();
    private final Set<Keyframe> selection;
    private final double anchor, first, last, pivot;
    private final int duration;
    boolean moved, scaling, collapsed;
    double scale = 1;

    CameraKeyDrag(Keyframe grabbed, Set<Keyframe> selection, double anchor, int duration) {
        this.grabbed = grabbed;
        this.selection = selection;
        this.anchor = anchor;
        this.duration = duration;
        first = selection.stream().mapToDouble(k -> k.tick).min().orElseThrow();
        last = selection.stream().mapToDouble(k -> k.tick).max().orElseThrow();
        pivot = grabbed.tick == first ? last : first;
    }

    void update(double pointer, boolean alt) {
        double distance = pointer - anchor;
        preview.clear();
        scaling = alt && first != last;
        collapsed = false;
        if (scaling) {
            double outer = pivot == first ? last : first;
            double extent = outer - pivot;
            double requested = 1 + distance / (grabbed.tick - pivot);
            double end = Math.clamp(Math.fma(extent, requested, pivot), 0, duration);
            scale = (end - pivot) / extent;
            if (scale == 1) return;
            var times = new HashSet<Double>();
            for (var key : selection) {
                double tick = key.tick == pivot ? pivot : key.tick == outer ? end
                    : Math.clamp(Math.fma(key.tick - pivot, scale, pivot), 0, duration);
                if (!times.add(tick)) {
                    // A zero-width selection cannot represent distinct camera poses.
                    preview.clear();
                    collapsed = true;
                    break;
                }
                preview.put(key, tick);
            }
        } else {
            double low = -first, high = duration - last;
            double delta = distance <= low ? low : distance >= high ? high : Math.clamp((double) Math.round(distance), low, high);
            if (delta == 0) return;
            for (var key : selection) preview.put(key, key.tick + delta);
        }
        moved = true;
    }
}
