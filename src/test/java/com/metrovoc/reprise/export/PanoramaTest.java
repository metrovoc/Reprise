package com.metrovoc.reprise.export;

import com.metrovoc.reprise.editor.ExportSettings;
import java.util.Arrays;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PanoramaTest {
    private static Quaternionf rotation(float yaw, float pitch, float roll) {
        return new Quaternionf().rotationYXZ((float) Math.toRadians(180 - yaw),
            (float) Math.toRadians(-pitch), (float) Math.toRadians(-roll));
    }

    @Test void everyCoverageSettingSamplesTheCenteredRectangleOfTheFullSphere() {
        var settings = new ExportSettings();
        settings.panorama = true;
        settings.width = 128;
        for (int fov = 120; fov <= 360; fov += 5) {
            settings.panoramaFov = fov;
            int full = settings.sphereWidth(), size = full / 4;
            int width = settings.outputWidth(), height = settings.outputHeight();
            assertEquals(64, height);
            assertEquals(fov, 360.0 * width / full, 360.0 / full);
            byte[][] faces = new byte[6][size * size * 4];
            for (int face = 0; face < faces.length; face++) {
                for (int pixel = 0; pixel < size * size; pixel++) {
                    faces[face][pixel * 4] = (byte) face;
                    faces[face][pixel * 4 + 1] = (byte) (pixel % size);
                    faces[face][pixel * 4 + 2] = (byte) (pixel / size);
                }
            }
            byte[] sphere = new Panorama(full, full / 2, size, false).project(faces);
            var projection = new Panorama(width, height, size, false);
            assertEquals(Panorama.Face.FRONT, projection.captureFaces.getLast());
            if (fov <= 270) assertTrue(!projection.captureFaces.contains(Panorama.Face.BACK));
            byte[] crop = projection.project(faces);
            for (int y = 0; y < height; y++) {
                int start = 4 * ((y + (full / 2 - height) / 2) * full + (full - width) / 2);
                assertTrue(Arrays.equals(sphere, start, start + 4 * width, crop, 4 * y * width, 4 * (y + 1) * width));
            }
        }
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
