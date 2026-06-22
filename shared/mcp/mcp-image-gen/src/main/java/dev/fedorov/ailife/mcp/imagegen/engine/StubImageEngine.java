package dev.fedorov.ailife.mcp.imagegen.engine;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * Default {@link ImageEngine}: produces a deterministic placeholder PNG (a warm-beige card with a
 * border) — no model, no cost, no GPU. It exists so the whole capability + binding + storage path is
 * built and testable now; the owner flips {@code image-gen.engine=local} to a real model later with
 * no caller change. Reference images are ignored (the placeholder is prompt-independent). Selected by
 * {@code image-gen.engine=stub} (the default).
 */
@Component
@ConditionalOnProperty(name = "image-gen.engine", havingValue = "stub", matchIfMissing = true)
public class StubImageEngine implements ImageEngine {

    private static final int W = 512;
    private static final int H = 683; // ~3:4

    @Override
    public GeneratedImage generate(String prompt, List<byte[]> refImages) {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(new Color(0xEF, 0xE7, 0xD8)); // noble beige
            g.fillRect(0, 0, W, H);
            g.setColor(new Color(0xD8, 0xCD, 0xB6)); // hairline
            g.drawRect(16, 16, W - 33, H - 33);
        } finally {
            g.dispose();
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return new GeneratedImage(out.toByteArray(), "image/png", "stub");
        } catch (Exception e) {
            throw new IllegalStateException("stub image render failed", e);
        }
    }
}
