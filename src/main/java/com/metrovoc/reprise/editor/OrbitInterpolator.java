package com.metrovoc.reprise.editor;

import java.util.ArrayList;
import java.util.List;

public final class OrbitInterpolator {
    private OrbitInterpolator() {}

    public static boolean sameOrbit(Keyframe a, Keyframe b) {
        return a.target != null && b.target != null && a.target.mode() == Keyframe.Tracking.ORBIT
            && b.target.mode() == Keyframe.Tracking.ORBIT && a.target.sameSubject(b.target);
    }

    /** Interpolate azimuth, elevation and distance around the moving subject. */
    public static CameraPose evaluate(List<Keyframe> keys, int left, double tick, double subjectX, double subjectY, double subjectZ,
                                      double eyeHeight, CameraPose pose) {
        int first = left > 0 && sameOrbit(keys.get(left - 1), keys.get(left)) ? left - 1 : left;
        int last = left + 1;
        if (last + 1 < keys.size() && sameOrbit(keys.get(last), keys.get(last + 1))) {
            last++;
        }
        List<Keyframe> polar = new ArrayList<>(last - first + 1);
        List<Keyframe> centers = keys.get(left).target.id() == null ? new ArrayList<>(last - first + 1) : null;
        double azimuth = 0;
        for (int i = first; i <= last; i++) {
            Keyframe source = keys.get(i);
            double x = source.x - source.target.x(), z = source.z - source.target.z();
            double y = source.y + eyeHeight - source.target.y();
            double angle = Math.toDegrees(Math.atan2(x, z));
            azimuth = i == first ? angle : azimuth + PathInterpolator.wrap(angle - azimuth) + keys.get(i - 1).turns * 360.0;
            Keyframe k = new Keyframe();
            k.tick = source.tick;
            k.interpolation = source.interpolation;
            k.x = azimuth;
            k.y = Math.atan2(y, Math.hypot(x, z));
            k.z = Math.sqrt(x * x + y * y + z * z);
            polar.add(k);
            if (centers != null) {
                Keyframe center = new Keyframe();
                center.tick = source.tick;
                center.interpolation = source.interpolation;
                center.x = source.target.x();
                center.y = source.target.y();
                center.z = source.target.z();
                centers.add(center);
            }
        }
        if (centers != null) {
            var center = PathInterpolator.evaluate(centers, tick);
            subjectX = center.x();
            subjectY = center.y();
            subjectZ = center.z();
        }
        CameraPose p = PathInterpolator.evaluate(polar, tick);
        double angle = Math.toRadians(p.x()), distance = Math.max(0, p.z()), horizontal = distance * Math.cos(p.y());
        return new CameraPose(subjectX + horizontal * Math.sin(angle), subjectY + distance * Math.sin(p.y()) - eyeHeight,
            subjectZ + horizontal * Math.cos(angle), pose.yaw(), pose.pitch(), pose.roll(), pose.fov()).aimAt(subjectX, subjectY, subjectZ, eyeHeight);
    }
}
