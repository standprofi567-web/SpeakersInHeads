package dev.klxsh.speakersinheads.audio;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpeakerGeometryTest {
    @Test
    void defaultRightVectorIsNormalized() {
        Vec3 right = SpeakerGeometry.getRightVector(null, new BlockPos(0, 60, 0));
        assertEquals(1.0, right.length(), 0.0001);
    }

    @Test
    void defaultForwardVectorIsNormalized() {
        Vec3 forward = SpeakerGeometry.getForwardVector(null, new BlockPos(0, 60, 0));
        assertEquals(1.0, forward.length(), 0.0001);
    }

    @Test
    void defaultBasisIsOrthonormal() {
        BlockPos pos = new BlockPos(0, 60, 0);
        Vec3 forward = SpeakerGeometry.getForwardVector(null, pos);
        Vec3 right = SpeakerGeometry.getRightVector(null, pos);
        Vec3 up = SpeakerGeometry.getUpVector(null, pos);

        assertEquals(1.0, forward.length(), 0.0001);
        assertEquals(1.0, right.length(), 0.0001);
        assertEquals(1.0, up.length(), 0.0001);

        // Dot products must be zero (perpendicular)
        assertEquals(0.0, forward.dot(right), 0.0001);
        assertEquals(0.0, forward.dot(up), 0.0001);
        assertEquals(0.0, right.dot(up), 0.0001);

        // Cross product right x forward = up
        Vec3 computedUp = right.cross(forward).normalize();
        assertEquals(up.x, computedUp.x, 0.0001);
        assertEquals(up.y, computedUp.y, 0.0001);
        assertEquals(up.z, computedUp.z, 0.0001);
    }

    @Test
    void defaultFrontFaceOriginPosition() {
        BlockPos pos = new BlockPos(10, 64, -20);
        Vec3 origin = SpeakerGeometry.getFrontFaceOrigin(null, pos);
        Vec3 forward = SpeakerGeometry.getForwardVector(null, pos);

        // Center is (10.5, 64.25, -19.5), face is forward by 0.26
        assertEquals(10.5 + (forward.x * 0.26), origin.x, 0.001);
        assertEquals(64.25 + (forward.y * 0.26), origin.y, 0.001);
        assertEquals(-19.5 + (forward.z * 0.26), origin.z, 0.001);
    }

    @Test
    void allFloorRotationsHaveOrthonormalBasis() {
        for (int rot = 0; rot < 16; rot++) {
            double rad = Math.toRadians((rot * 360.0) / 16.0);
            Vec3 forward = new Vec3(Math.sin(rad), 0, -Math.cos(rad)).normalize();
            Vec3 right = new Vec3(Math.cos(rad), 0, Math.sin(rad)).normalize();
            Vec3 up = right.cross(forward).normalize();

            assertEquals(1.0, forward.length(), 0.0001);
            assertEquals(1.0, right.length(), 0.0001);
            assertEquals(1.0, up.length(), 0.0001);

            assertEquals(0.0, forward.dot(right), 0.0001, "Rotation " + rot + " forward and right should be perpendicular");
            assertEquals(0.0, forward.dot(up), 0.0001, "Rotation " + rot + " forward and up should be perpendicular");
            assertEquals(0.0, right.dot(up), 0.0001, "Rotation " + rot + " right and up should be perpendicular");

            assertEquals(0.0, up.x, 0.0001);
            assertEquals(1.0, up.y, 0.0001);
            assertEquals(0.0, up.z, 0.0001);
        }
    }

    @Test
    void floorDiagonalRotationsFaceForwardCorrectly() {
        // Rot 0 is North (0, 0, -1)
        double rad0 = Math.toRadians(0.0);
        Vec3 f0 = new Vec3(Math.sin(rad0), 0, -Math.cos(rad0)).normalize();
        assertEquals(0.0, f0.x, 0.0001);
        assertEquals(-1.0, f0.z, 0.0001);

        // Rot 2 is North-East (45 deg): x > 0, z < 0
        double rad2 = Math.toRadians(45.0);
        Vec3 f2 = new Vec3(Math.sin(rad2), 0, -Math.cos(rad2)).normalize();
        assertTrue(f2.x > 0.7, "Rot 2 must point towards East (+X)");
        assertTrue(f2.z < -0.7, "Rot 2 must point towards North (-Z)");

        // Rot 4 is East (1, 0, 0)
        double rad4 = Math.toRadians(90.0);
        Vec3 f4 = new Vec3(Math.sin(rad4), 0, -Math.cos(rad4)).normalize();
        assertEquals(1.0, f4.x, 0.0001);
        assertEquals(0.0, f4.z, 0.0001);

        // Rot 6 is South-East (135 deg): x > 0, z > 0
        double rad6 = Math.toRadians(135.0);
        Vec3 f6 = new Vec3(Math.sin(rad6), 0, -Math.cos(rad6)).normalize();
        assertTrue(f6.x > 0.7, "Rot 6 must point towards East (+X)");
        assertTrue(f6.z > 0.7, "Rot 6 must point towards South (+Z)");

        // Rot 8 is South (0, 0, 1)
        double rad8 = Math.toRadians(180.0);
        Vec3 f8 = new Vec3(Math.sin(rad8), 0, -Math.cos(rad8)).normalize();
        assertEquals(0.0, f8.x, 0.0001);
        assertEquals(1.0, f8.z, 0.0001);

        // Rot 10 is South-West (225 deg): x < 0, z > 0
        double rad10 = Math.toRadians(225.0);
        Vec3 f10 = new Vec3(Math.sin(rad10), 0, -Math.cos(rad10)).normalize();
        assertTrue(f10.x < -0.7, "Rot 10 must point towards West (-X)");
        assertTrue(f10.z > 0.7, "Rot 10 must point towards South (+Z)");

        // Rot 12 is West (-1, 0, 0)
        double rad12 = Math.toRadians(270.0);
        Vec3 f12 = new Vec3(Math.sin(rad12), 0, -Math.cos(rad12)).normalize();
        assertEquals(-1.0, f12.x, 0.0001);
        assertEquals(0.0, f12.z, 0.0001);

        // Rot 14 is North-West (315 deg): x < 0, z < 0
        double rad14 = Math.toRadians(315.0);
        Vec3 f14 = new Vec3(Math.sin(rad14), 0, -Math.cos(rad14)).normalize();
        assertTrue(f14.x < -0.7, "Rot 14 must point towards West (-X)");
        assertTrue(f14.z < -0.7, "Rot 14 must point towards North (-Z)");
    }

    @Test
    void stereoPositionsAreOffsetEquallyFromCenter() {
        BlockPos pos = new BlockPos(10, 64, -20);
        SpeakerGeometry.StereoPositions stereo = SpeakerGeometry.calculateStereoPositions(null, pos, 0.35);

        assertEquals(10.5, stereo.center().x, 0.001);
        assertEquals(64.25, stereo.center().y, 0.001);
        assertEquals(-19.5, stereo.center().z, 0.001);

        double distLeft = stereo.center().distanceTo(stereo.left());
        double distRight = stereo.center().distanceTo(stereo.right());
        assertEquals(0.35, distLeft, 0.001);
        assertEquals(0.35, distRight, 0.001);

        double distBetweenSpeakers = stereo.left().distanceTo(stereo.right());
        assertEquals(0.70, distBetweenSpeakers, 0.001);
    }
}
