package com.metrovoc.reprise.playback;

import com.metrovoc.reprise.format.PovSamples.Pose;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.util.Mth;

/** The recorder moves locally every tick. Network smoothing is only appropriate for the other players. */
final class RecorderMotion {
    private Pose previous, current, next, start, end;
    private float walk, stride, bob, oldBob;
    private float nextSpeed, speed;
    private boolean ground, nextGround;
    private float viewWalk, viewStride, viewBob, viewOldBob;

    void spawn(Pose pose) {
        start = end = previous = current = next = pose;
        walk = stride = bob = oldBob = viewWalk = viewStride = viewBob = viewOldBob = speed = nextSpeed = 0;
        ground = nextGround = false;
    }

    void move(ClientboundEntityPositionSyncPacket packet) {
        var v = packet.values();
        var p = v.position();
        next = new Pose(p.x, p.y, p.z, v.yRot(), v.xRot());
        nextSpeed = (float)v.deltaMovement().horizontalDistance();
        nextGround = packet.onGround();
    }

    void advance() {
        if (current == null) return;
        // During the interval just recorded, feet interpolated from the last two simulation positions.
        // Mouse input continued until the next tick, independent of that position interpolation.
        start = new Pose(previous.x(), previous.y(), previous.z(), current.yaw(), current.pitch());
        end = new Pose(current.x(), current.y(), current.z(), next.yaw(), next.pitch());
        viewWalk = walk; viewStride = stride; viewOldBob = oldBob; viewBob = bob;
        double distance = Math.hypot(next.x()-current.x(), next.z()-current.z());
        stride = distance > 8 ? 0 : (float)distance * .6f;
        walk += stride;
        oldBob = bob;
        bob += ((ground ? Math.min(.1f, speed) : 0) - bob) * .4f;
        previous = current; current = next; speed = nextSpeed; ground = nextGround;
    }

    Pose at(float fraction) { return start == null ? null : start.interpolate(end, fraction); }
    float walk(float f) { return -(viewWalk + viewStride * f); }
    float bob(float f) { return Mth.lerp(f, viewOldBob, viewBob); }
}
