package com.metrovoc.reprise.editor;

public record CameraPose(double x, double y, double z, float yaw, float pitch, float roll, float fov) {
    public CameraPose aimAt(double targetX, double targetY, double targetZ, double eyeHeight) {
        double dx = targetX - this.x, dz = targetZ - this.z;
        double dy = targetY - (this.y + eyeHeight);
        double horizontal = Math.hypot(dx, dz);
        float yaw = horizontal < 1e-7 ? this.yaw : (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = horizontal < 1e-7 && Math.abs(dy) < 1e-7 ? this.pitch : (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        return new CameraPose(this.x, this.y, this.z, yaw, pitch, this.roll, this.fov);
    }
}
