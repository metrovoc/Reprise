package com.metrovoc.reprise.export;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PanoramaTest {
    private static Quaternionf rotation(float yaw, float pitch, float roll) {
        return new Quaternionf().rotationYXZ((float) Math.toRadians(180 - yaw),
            (float) Math.toRadians(-pitch), (float) Math.toRadians(-roll));
    }

    @Test void allFacesKeepTheirOrientationThroughPolesAndCameraRoll() {
        for (float pitch : new float[]{-90, -27, 0, 65, 90}) {
            for (float roll : new float[]{0, 36, 90, 180}) {
                var shot = rotation(123, pitch, roll);
                for (var face : Panorama.Face.values()) {
                    var angles = Panorama.angles(face, 123, pitch, roll);
                    var actual = rotation(angles.x, angles.y, angles.z);
                    var forward = new Vector3f();
                    var up = new Vector3f(0, 1, 0);
                    switch (face) {
                        case FRONT -> forward.z = -1;
                        case BACK -> forward.z = 1;
                        case RIGHT -> forward.x = 1;
                        case LEFT -> forward.x = -1;
                        case TOP -> { forward.y = 1; up.set(0, 0, 1); }
                        case BOTTOM -> { forward.y = -1; up.set(0, 0, -1); }
                    }
                    assertTrue(actual.transform(new Vector3f(0, 0, -1)).distance(shot.transform(forward)) < 1e-5, face.name());
                    assertTrue(actual.transform(new Vector3f(0, 1, 0)).distance(shot.transform(up)) < 1e-5, face.name());
                }
            }
        }
    }
}
