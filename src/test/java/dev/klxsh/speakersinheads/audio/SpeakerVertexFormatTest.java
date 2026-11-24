package dev.klxsh.speakersinheads.audio;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests vertex building compliance with Minecraft 26.3's {@link DefaultVertexFormat#POSITION_COLOR_NORMAL_LINE_WIDTH}.
 * Verifies that all required vertex attributes (Position, Color, Normal, LineWidth) are completely
 * filled so that BufferBuilder.endLastVertex() does not throw IllegalStateException.
 */
class SpeakerVertexFormatTest {

    @Test
    void completeVertexAttributesDoNotThrowMissingElements() {
        ByteBufferBuilder pool = new ByteBufferBuilder(2048);
        try {
            BufferBuilder builder = new BufferBuilder(pool, PrimitiveTopology.LINES,
                    DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH);

            // First vertex with all 4 mandatory attributes
            builder.addVertex(0.0f, 0.0f, 0.0f)
                    .setColor(48, 208, 240, 180)
                    .setNormal(1.0f, 0.0f, 0.0f)
                    .setLineWidth(2.5f);

            // Second vertex with all 4 mandatory attributes
            builder.addVertex(1.0f, 0.0f, 0.0f)
                    .setColor(48, 208, 240, 180)
                    .setNormal(1.0f, 0.0f, 0.0f)
                    .setLineWidth(2.5f);

            MeshData mesh = builder.build();
            assertNotNull(mesh, "Mesh should build successfully when all vertex attributes are present");
            mesh.close();
        } finally {
            pool.close();
        }
    }

    @Test
    void missingLineWidthAttributeThrowsIllegalStateException() {
        ByteBufferBuilder pool = new ByteBufferBuilder(2048);
        try {
            BufferBuilder builder = new BufferBuilder(pool, PrimitiveTopology.LINES,
                    DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH);

            // First vertex missing setLineWidth
            builder.addVertex(0.0f, 0.0f, 0.0f)
                    .setColor(48, 208, 240, 180)
                    .setNormal(1.0f, 0.0f, 0.0f);
            // Omitting .setLineWidth(2.5f);

            // Beginning second vertex must trigger BufferBuilder.endLastVertex() check and throw
            assertThrows(IllegalStateException.class, () -> {
                builder.addVertex(1.0f, 0.0f, 0.0f);
            }, "BufferBuilder should reject vertex when LineWidth attribute is missing");
        } finally {
            pool.close();
        }
    }
}
