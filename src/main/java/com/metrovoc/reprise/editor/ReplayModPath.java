package com.metrovoc.reprise.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/** Converts ReplayMod's separate millisecond time and camera tracks to editable replay-tick keys. */
final class ReplayModPath {
    private record Clock(double time, double replay) {}
    private record Pose(double time, double[] values, Integer spectator) {}
    private record Curve(Pose a, Pose b, double[] left, double[] right, boolean linear, boolean spectating) {
        double[] at(double time) {
            double u = (time - a.time) / (b.time - a.time);
            double[] result = new double[6];
            for (int c = 0; c < 6; c++) {
                double delta = b.values[c] - a.values[c];
                result[c] = a.values[c] + u * (left[c] + u * (3 * delta - 2 * left[c] - right[c]
                    + u * (left[c] + right[c] - 2 * delta)));
            }
            return result;
        }
    }

    final List<Keyframe> keys = new ArrayList<>();
    final List<SpeedKey> speeds = new ArrayList<>();
    final List<ReplayProject.Freeze> freezes = new ArrayList<>();
    final int start, end;

    ReplayModPath(JsonArray tracks, int duration, CameraPose initial) {
        float fov = initial.fov();
        require(tracks.size() == 2, "Expected a time track and a camera track.");
        var timing = tracks.get(0).getAsJsonObject();
        var clock = new ArrayList<Clock>();
        for (var entry : timing.getAsJsonArray("keyframes")) {
            var key = entry.getAsJsonObject();
            clock.add(new Clock(key.get("time").getAsLong(), key.getAsJsonObject("properties").get("timestamp").getAsInt()));
        }
        require(clock.size() >= 2, "This path needs at least two time keys.");
        var camera = tracks.get(1).getAsJsonObject();
        var poses = new ArrayList<Pose>();
        for (var entry : camera.getAsJsonArray("keyframes")) {
            var key = entry.getAsJsonObject();
            var props = key.getAsJsonObject("properties");
            Integer spectator = props.has("spectate") ? props.get("spectate").getAsInt() : null;
            double[] values = new double[6];
            for (int i = 0; i < values.length; i++) {
                values[i] = props.getAsJsonArray(i < 3 ? "camera:position" : "camera:rotation").get(i % 3).getAsDouble();
                require(Double.isFinite(values[i]), "Invalid camera coordinate.");
            }
            var pose = new Pose(key.get("time").getAsLong(), values, spectator);
            if (!poses.isEmpty()) {
                var previous = poses.getLast();
                require(pose.time > previous.time, "Camera keys must be in time order.");
                for (int i = 3; i < 6; i++) {
                    double prior = previous.values[i];
                    double delta = ((values[i] % 360 + 360) % 360) - ((prior % 360 + 360) % 360);
                    if (Math.abs(delta) > 180) delta -= Math.copySign(360, delta);
                    values[i] = prior + delta;
                }
            }
            poses.add(pose);
        }
        require(poses.size() >= 2, "This path needs at least two camera keys.");
        // Camera motion beyond the timestamp track holds the nearest recorded instant.
        double firstTime = clock.getFirst().time, lastTime = clock.getLast().time;
        for (int i = 1; i < clock.size(); i++) {
            require(type(interpolator(timing, i - 1), "timestamp").getAsString().equals("linear"), "Unsupported time interpolation.");
        }
        if (poses.getFirst().time < firstTime) clock.addFirst(new Clock(poses.getFirst().time, clock.getFirst().replay));
        if (poses.getLast().time > lastTime) clock.add(new Clock(poses.getLast().time, clock.getLast().replay));
        require(clock.getFirst().replay >= 0 && clock.getLast().replay <= duration * 50.0, "Time track extends beyond this recording.");
        int extra = 0;
        for (int i = 1; i < clock.size(); i++) {
            var a = clock.get(i - 1);
            var b = clock.get(i);
            double from = a.replay / 50;
            double worldEnd = b.replay / 50;
            double shotEndMs = b.replay + extra * 50.0;
            require(b.time > a.time && shotEndMs >= a.replay, "Reversed time segments cannot be imported.");
            if (shotEndMs == a.replay) {
                int length = Math.toIntExact((long) Math.ceil((b.time - a.time) / 50));
                extra = Math.addExact(extra, length);
                freezes.add(new ReplayProject.Freeze(worldEnd, length));
                shotEndMs += length * 50.0;
            }
            require(duration + (long) extra <= Integer.MAX_VALUE, "Camera timeline is too long.");
            var speed = new SpeedKey(from, (shotEndMs - a.replay) / (b.time - a.time));
            require(speed.speed >= 0.001, "Time segment is below the minimum replay speed.");
            speed.hold = true;
            speeds.add(speed);
            clock.set(i, new Clock(b.time, shotEndMs));
        }
        start = (int) Math.floor(clock.getFirst().replay / 50);
        end = (int) Math.ceil(clock.getLast().replay / 50);
        if (poses.getFirst().time > clock.getFirst().time) {
            // ReplayMod does not move the camera until its first position key.
            var lead = key(clock.getFirst().replay / 50, new double[]{initial.x(), initial.y(), initial.z(),
                initial.yaw(), initial.pitch(), initial.roll()}, fov);
            lead.interpolation = Keyframe.Interpolation.HOLD;
            keys.add(lead);
        }
        double lastCameraTick = 0;
        int clockIndex = 0;
        Curve previous = null;
        for (var curve : curves(camera, poses)) {
            double left = curve.a.time;
            while (left < curve.b.time) {
                while (clock.get(clockIndex + 1).time <= left) clockIndex++;
                var a = clock.get(clockIndex);
                var b = clock.get(clockIndex + 1);
                double right = Math.min(curve.b.time, b.time);
                double slope = (b.replay - a.replay) / (b.time - a.time);
                double tick = (a.replay + (left - a.time) * slope) / 50;
                var key = cameraKey(curve, left, right, tick, fov);
                key.spectatorAfterKey = curve.spectating ? curve.a.spectator : null;
                // ReplayStudio gives the preceding segment ownership of a shared endpoint.
                boolean spectatorAtStart = left == curve.a.time && previous != null ? previous.spectating : curve.spectating;
                key.spectatorAtKey = spectatorAtStart ? curve.a.spectator : null;
                keys.add(key);
                left = right;
                lastCameraTick = (a.replay + (right - a.time) * slope) / 50;
            }
            previous = curve;
        }
        var last = key(lastCameraTick, poses.getLast().values, fov);
        if (previous.spectating) last.spectatorAtKey = last.spectatorAfterKey = previous.b.spectator;
        keys.add(last);
    }

    private static JsonObject interpolator(JsonObject path, int index) {
        var segment = path.getAsJsonArray("segments").get(index);
        require(!segment.isJsonNull(), "An unconnected path segment cannot be imported.");
        return path.getAsJsonArray("interpolators").get(segment.getAsInt()).getAsJsonObject();
    }

    private static JsonElement type(JsonObject interpolator, String... required) {
        var properties = interpolator.getAsJsonArray("properties");
        for (String name : required) require(properties.contains(new com.google.gson.JsonPrimitive(name)), "Missing interpolation for " + name);
        return interpolator.get("type");
    }

    private static List<Curve> curves(JsonObject path, List<Pose> poses) {
        var result = new ArrayList<Curve>();
        var segments = path.getAsJsonArray("segments");
        require(segments.size() == poses.size() - 1, "Incomplete camera track.");
        double[] velocity = new double[6], acceleration = new double[6];
        boolean hasCameraDerivatives = false;
        for (int first = 0; first < segments.size();) {
            int limit = first + 1;
            while (limit < segments.size() && segments.get(limit).equals(segments.get(first))) limit++;
            var interpolator = interpolator(path, first);
            boolean spectating = interpolator.getAsJsonArray("properties").contains(new com.google.gson.JsonPrimitive("spectate"));
            var spec = spectating ? interpolator.get("type") : type(interpolator, "camera:position", "camera:rotation");
            String name = spec.isJsonObject() ? spec.getAsJsonObject().get("type").getAsString() : spec.getAsString();
            require(!spectating || name.equals("linear"), "Unsupported spectator interpolation.");
            var knots = poses.subList(first, limit + 1);
            double[][] second = null;
            double alpha = 0;
            switch (name) {
                case "linear" -> {}
                case "catmull-rom-spline" -> {
                    alpha = spec.getAsJsonObject().get("alpha").getAsDouble();
                    require(Double.isFinite(alpha), "Invalid Catmull–Rom tension.");
                }
                case "cubic-spline" -> { if (!hasCameraDerivatives) second = naturalSpline(knots); }
                default -> throw new IllegalArgumentException("Unsupported camera interpolation: " + name);
            }
            for (int i = 0; i + 1 < knots.size(); i++) {
                var a = knots.get(i);
                var b = knots.get(i + 1);
                require(!spectating || (a.spectator != null && b.spectator != null), "Missing spectator entity.");
                double[] left = new double[6], right = new double[6];
                double seconds = (b.time - a.time) / 1000;
                for (int c = 0; c < 6; c++) {
                    double delta = b.values[c] - a.values[c];
                    if (second != null) {
                        double scale = Math.pow(b.time - a.time, 2) / 6;
                        left[c] = delta - scale * (2 * second[i][c] + second[i + 1][c]);
                        right[c] = delta + scale * (second[i][c] + 2 * second[i + 1][c]);
                    } else if (name.equals("cubic-spline")) {
                        // A following cubic inherits the previous interpolator's ending derivatives.
                        left[c] = velocity[c] * seconds;
                        right[c] = 3 * delta - 2 * left[c] - acceleration[c] * seconds * seconds / 2;
                    } else if (name.equals("catmull-rom-spline")) {
                        left[c] = alpha * (b.values[c] - knots.get(Math.max(0, i - 1)).values[c]);
                        right[c] = alpha * (knots.get(Math.min(knots.size() - 1, i + 2)).values[c] - a.values[c]);
                    } else {
                        left[c] = right[c] = delta;
                    }
                    // ReplayStudio passes linear (1,0), Catmull–Rom derivatives per segment,
                    // and cubic derivatives per second to the next interpolation group.
                    double unit = name.equals("cubic-spline") ? seconds : 1;
                    velocity[c] = name.equals("linear") ? 1 : right[c] / unit;
                    acceleration[c] = name.equals("linear") ? 0 : (2 * left[c] + 4 * right[c] - 6 * delta) / (unit * unit);
                }
                result.add(new Curve(a, b, left, right, name.equals("linear"), spectating));
            }
            hasCameraDerivatives = !spectating;
            first = limit;
        }
        return result;
    }

    private static double[][] naturalSpline(List<Pose> points) {
        // Tridiagonal solve for the second derivatives, with zero curvature at both ends.
        int n = points.size();
        double[][] values = new double[n][6];
        double[] upper = new double[n];
        for (int i = 1; i < n - 1; i++) {
            var a = points.get(i - 1);
            var b = points.get(i);
            var c = points.get(i + 1);
            double h = b.time - a.time, k = c.time - b.time;
            double diagonal = 2 * (h + k) - h * upper[i - 1];
            upper[i] = k / diagonal;
            for (int j = 0; j < 6; j++) {
                double rateChange = (c.values[j] - b.values[j]) / k - (b.values[j] - a.values[j]) / h;
                values[i][j] = (6 * rateChange - h * values[i - 1][j]) / diagonal;
            }
        }
        for (int i = n - 2; i >= 1; i--) {
            for (int j = 0; j < 6; j++) values[i][j] -= upper[i] * values[i + 1][j];
        }
        return values;
    }

    private static Keyframe cameraKey(Curve curve, double a, double b, double tick, float fov) {
        var start = curve.at(a);
        var end = curve.at(b);
        var key = key(tick, start, fov);
        if (curve.linear && Math.abs(end[3] - start[3]) < 180 && Math.abs(end[5] - start[5]) < 180) return key;
        // A new time slope can split a cubic between its authored keys. Two interior samples
        // determine the endpoint tangent offsets for that exact subcurve in the new time domain.
        var p = curve.at(a + (b - a) / 3);
        var q = curve.at(b - (b - a) / 3);
        double[] left = new double[6], right = new double[6];
        for (int i = 0; i < 6; i++) {
            double delta = end[i] - start[i];
            double e = p[i] - start[i] - delta / 3;
            double f = q[i] - start[i] - 2 * delta / 3;
            left[i] = 9 * e - 4.5 * f;
            right[i] = 9 * f - 4.5 * e;
        }
        key.spline = new Keyframe.Spline(left, right);
        key.interpolation = Keyframe.Interpolation.SMOOTH;
        return key;
    }

    private static Keyframe key(double tick, double[] values, float fov) {
        var key = new Keyframe();
        key.tick = tick;
        key.x = values[0];
        key.y = values[1];
        key.z = values[2];
        key.yaw = (float) values[3];
        key.pitch = (float) values[4];
        key.roll = (float) values[5];
        key.fov = fov;
        key.interpolation = Keyframe.Interpolation.LINEAR;
        return key;
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new IllegalArgumentException(message);
    }
}
