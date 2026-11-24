package dev.klxsh.speakersinheads.ui;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.UUID;

final class ServiceArtwork implements AutoCloseable {
    record Bounds(int left, int top, int right, int bottom) {}

    static Bounds bounds(int left, int top, int size) {
        return new Bounds(left, top, left + size, top + size);
    }

    static void draw(net.minecraft.client.gui.GuiGraphicsExtractor graphics, Identifier id, int x, int y, int size) {
        var b = bounds(x, y, size);
        graphics.blit(id, b.left(), b.top(), b.right(), b.bottom(), 0f, 1f, 0f, 1f);
    }
    private static final int SIZE = 96;
    private static final int CAPACITY = 8;
    private final LinkedHashMap<String, Identifier> textures = new LinkedHashMap<>(16, 0.75f, true);
    private Thread worker;
    private boolean closed;
    private String unavailable = "";

    Identifier find(String key) { return textures.get(key); }

    @FunctionalInterface
    interface ImageRequest { byte[] get() throws IOException; }

    void request(String key, ImageRequest request) {
        if (key.isBlank() || closed || worker != null || unavailable.equals(key) || textures.containsKey(key)) return;
        worker = Thread.ofVirtual().name("sih-cover").start(() -> {
            int[] pixels = null;
            try {
                pixels = decode(request.get());
            } catch (IOException | RuntimeException ignored) {
                // Covers are optional. Provider URLs and request details stay out of logs.
            }
            int[] image = pixels;
            Minecraft.getInstance().execute(() -> {
                worker = null;
                if (closed) return;
                if (image == null) {
                    unavailable = key;
                } else {
                    install(key, image);
                }
            });
        });
    }

    private static int[] decode(byte[] bytes) throws IOException {
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) return null;
            var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > 1024 || height > 1024) return null;
                BufferedImage thumbnail = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
                var graphics = thumbnail.createGraphics();
                try {
                    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    double scale = (double) SIZE / Math.max(width, height);
                    int w = (int) Math.ceil(width * scale), h = (int) Math.ceil(height * scale);
                    graphics.drawImage(reader.read(0), (SIZE - w) / 2, (SIZE - h) / 2, w, h, null);
                } finally {
                    graphics.dispose();
                }
                return thumbnail.getRGB(0, 0, SIZE, SIZE, null, 0, SIZE);
            } finally {
                reader.dispose();
            }
        }
    }

    private void install(String key, int[] pixels) {
        var manager = Minecraft.getInstance().getTextureManager();
        if (textures.size() == CAPACITY) manager.release(textures.pollFirstEntry().getValue());
        var id = Identifier.fromNamespaceAndPath("speakersinheads", "cover/" + UUID.randomUUID());
        var image = new NativeImage(SIZE, SIZE, false);
        DynamicTexture texture = null;
        try {
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) image.setPixel(x, y, pixels[y * SIZE + x]);
            }
            texture = new DynamicTexture(() -> "SpeakersInHeads album cover", image);
            manager.register(id, texture);
            textures.put(key, id);
        } catch (RuntimeException failure) {
            if (texture == null) image.close();
            else texture.close();
            unavailable = key;
        }
    }

    @Override public void close() {
        closed = true;
        if (worker != null) worker.interrupt();
        var manager = Minecraft.getInstance().getTextureManager();
        textures.values().forEach(manager::release);
        textures.clear();
    }
}
