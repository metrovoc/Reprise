package com.metrovoc.reprise.editor;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrbitInterpolatorTest {
    private static final UUID SUBJECT = new UUID(0, 1);
    private static final CameraPose LENS = new CameraPose(0, 0, 0, 0, 0, 12, 55);

    private static Keyframe key(int tick, double center, double angle, double radius) {
        Keyframe key = new Keyframe();
        key.tick = tick;
        key.target = new Keyframe.Target(SUBJECT, "Actor", Keyframe.Tracking.ORBIT, Keyframe.AimPoint.EYES, center, 5, 0);
        key.x = center + radius * Math.sin(Math.toRadians(angle));
        key.y = 3.5;
        key.z = radius * Math.cos(Math.toRadians(angle));
        key.interpolation = Keyframe.Interpolation.LINEAR;
        return key;
    }

    private static CameraPose at(Keyframe a, Keyframe b, int tick) {
        return OrbitInterpolator.evaluate(List.of(a, b), 0, tick, 30, 5, 0, 1.5, LENS);
    }

    @Test void extraTurnsCarryTheCameraAroundAMovingSubjectInEitherDirection() {
        Keyframe a = key(0, 5, 0, 10), b = key(100, 20, 0, 10);
        for (int turns : new int[]{-2, -1, 1, 2}) {
            a.turns = turns;
            for (int t = 0; t <= 100; t++) {
                CameraPose p = at(a, b, t);
                double angle = Math.PI * 2 * turns * t / 100;
                assertEquals(30 + 10 * Math.sin(angle), p.x(), 1e-8);
                assertEquals(10 * Math.cos(angle), p.z(), 1e-8);
                assertEquals(3.5, p.y(), 1e-8);
                assertEquals(12, p.roll());
                assertEquals(55, p.fov());
            }
        }
    }

    @Test void crossesTheAngleSeamAlongTheShortArcAndInterpolatesDistance() {
        CameraPose mid = at(key(0, 5, 175, 5), key(100, 10, -175, 15), 50);
        assertEquals(30, mid.x(), 1e-8);
        assertEquals(-10, mid.z(), 1e-8);
    }

    @Test void worldOrbitInterpolatesThePivotBetweenKeys() {
        Keyframe a = key(0, 2, 180, 10), b = key(100, 10, 90, 10);
        for (var k : List.of(a, b)) {
            k.target = new Keyframe.Target(null, "Pivot", Keyframe.Tracking.ORBIT, Keyframe.AimPoint.CENTER, k.target.x(), 5, 0);
        }
        CameraPose p = at(a, b, 50);
        assertEquals(6 + Math.sqrt(50), p.x(), 1e-8);
        assertEquals(-Math.sqrt(50), p.z(), 1e-8);
        assertEquals(45, p.yaw(), 1e-5);
    }

    @Test void holdFollowsWithoutRotatingUntilTheCut() {
        Keyframe a = key(0, 0, 90, 5), b = key(100, 0, 0, 10);
        a.interpolation = Keyframe.Interpolation.HOLD;
        assertEquals(35, at(a, b, 99).x(), 1e-8);
        assertEquals(10, at(a, b, 100).z(), 1e-8);
    }

    @Test void verticalAndCoincidentTargetsPreserveAUsableHeading() {
        CameraPose pose = new CameraPose(1, 2, 3, 37, 12, 8, 70);
        assertEquals(-90, pose.aimAt(1, 8, 3, 1).pitch());
        assertEquals(37, pose.aimAt(1, 8, 3, 1).yaw());
        assertEquals(pose, pose.aimAt(1, 3, 3, 1));
    }
}
