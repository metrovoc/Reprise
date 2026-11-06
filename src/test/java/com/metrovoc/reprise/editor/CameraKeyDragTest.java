package com.metrovoc.reprise.editor;

import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CameraKeyDragTest {
    private static Keyframe key(double tick) {
        var key = new Keyframe();
        key.tick = tick;
        return key;
    }

    @Test void interiorHandleCanStretchCompressAndReverseAtEitherEdge() {
        var first = key(40.125);
        var middle = key(45.125);
        var last = key(60.125);
        var drag = new CameraKeyDrag(middle, Set.of(first, middle, last), 45, 100);
        drag.update(47.5, true);
        assertEquals(70.125, drag.preview.get(last));
        drag.update(42.5, true);
        assertEquals(50.125, drag.preview.get(last));
        drag.update(40, true);
        assertTrue(drag.collapsed);
        assertTrue(drag.preview.isEmpty());
        drag.update(35, true);
        assertEquals(20.125, drag.preview.get(last));
        assertEquals(40.125, drag.preview.get(first));
        drag.update(10, true);
        assertEquals(0, drag.preview.get(last));
        drag.update(95, true);
        assertEquals(100, drag.preview.get(last));
        assertEquals(45.125, middle.tick);
    }

    @Test void earliestHandleKeepsTheLastKeyAndRespondsToSubTickMovement() {
        var first = key(20);
        var last = key(20.25);
        var drag = new CameraKeyDrag(first, Set.of(first, last), 20, 100);
        drag.update(20.125, true);
        assertEquals(20.125, drag.preview.get(first));
        assertEquals(20.25, drag.preview.get(last));
        drag.update(21, false);
        assertEquals(21, drag.preview.get(first));
        assertEquals(21.25, drag.preview.get(last));
    }
}
