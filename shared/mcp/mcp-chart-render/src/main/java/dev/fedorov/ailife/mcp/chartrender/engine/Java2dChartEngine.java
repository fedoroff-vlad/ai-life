package dev.fedorov.ailife.mcp.chartrender.engine;

import dev.fedorov.ailife.contracts.chart.ChartSeries;
import dev.fedorov.ailife.contracts.chart.ChartSpec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Default {@link ChartEngine}: renders a {@link ChartSpec} into a PNG with pure {@code java.awt}
 * ({@code Graphics2D} + {@code ImageIO}) — no external charting library, no cost, deterministic. Mirrors
 * {@code mcp-image-gen}'s {@code StubImageEngine} (both draw with {@code Graphics2D} + {@code ImageIO}),
 * but this one actually plots the data. Supports {@code bar} (grouped when multi-series), {@code line}
 * (one polyline per series), and {@code pie} (first series' shares). Selected by
 * {@code chart-render.engine=java2d} (the default).
 */
@Component
@ConditionalOnProperty(name = "chart-render.engine", havingValue = "java2d", matchIfMissing = true)
public class Java2dChartEngine implements ChartEngine {

    private static final int W = 900;
    private static final int H = 560;
    private static final int PAD = 48;           // outer margin
    private static final int TITLE_H = 52;       // reserved for the title row
    private static final int LEGEND_H = 28;      // reserved when a legend is drawn

    private static final Color BG = Color.WHITE;
    private static final Color AXIS = new Color(0x9A, 0xA0, 0xA6);
    private static final Color GRID = new Color(0xE8, 0xEA, 0xED);
    private static final Color TEXT = new Color(0x3C, 0x40, 0x43);
    private static final Color TITLE = new Color(0x20, 0x23, 0x24);
    // A calm, distinguishable palette (Material-ish) reused across bars/lines/pie slices.
    private static final Color[] PALETTE = {
            new Color(0x42, 0x85, 0xF4), new Color(0xEA, 0x43, 0x35), new Color(0xFB, 0xBC, 0x05),
            new Color(0x34, 0xA8, 0x53), new Color(0xA1, 0x42, 0xF4), new Color(0x00, 0xAC, 0xC1),
            new Color(0xFF, 0x70, 0x43), new Color(0x9E, 0x9D, 0x24),
    };

    @Override
    public RenderedChart render(ChartSpec spec) {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(BG);
            g.fillRect(0, 0, W, H);

            drawTitle(g, spec.title());

            String type = spec.type() == null ? "" : spec.type().trim().toLowerCase();
            switch (type) {
                case "pie" -> drawPie(g, spec);
                case "line" -> drawCartesian(g, spec, false);
                default -> drawCartesian(g, spec, true); // bar (and fallback)
            }
        } finally {
            g.dispose();
        }
        return new RenderedChart(toPng(img), "image/png", "java2d");
    }

    // --- title ---------------------------------------------------------------

    private void drawTitle(Graphics2D g, String title) {
        if (title == null || title.isBlank()) return;
        g.setColor(TITLE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 22));
        int x = (W - g.getFontMetrics().stringWidth(title)) / 2;
        g.drawString(title, Math.max(PAD, x), 34);
    }

    // --- bar / line ----------------------------------------------------------

    private void drawCartesian(Graphics2D g, ChartSpec spec, boolean bars) {
        List<ChartSeries> series = nonEmptySeries(spec);
        List<String> categories = spec.categories() == null ? List.of() : spec.categories();
        if (series.isEmpty() || categories.isEmpty()) {
            drawEmpty(g);
            return;
        }
        boolean legend = series.size() > 1;

        int plotTop = TITLE_H;
        int plotBottom = H - PAD - 24 - (legend ? LEGEND_H : 0); // room for x labels (+ legend)
        int plotLeft = PAD + 56;                                  // room for y labels
        int plotRight = W - PAD;

        double max = maxValue(series);
        double niceMax = niceCeil(max <= 0 ? 1 : max);

        // y gridlines + labels (5 steps)
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        int steps = 5;
        for (int i = 0; i <= steps; i++) {
            double frac = (double) i / steps;
            int y = (int) (plotBottom - frac * (plotBottom - plotTop));
            g.setColor(GRID);
            g.drawLine(plotLeft, y, plotRight, y);
            g.setColor(TEXT);
            String lbl = formatValue(niceMax * frac, spec.unit());
            g.drawString(lbl, plotLeft - 8 - g.getFontMetrics().stringWidth(lbl), y + 4);
        }
        // axes
        g.setColor(AXIS);
        g.setStroke(new BasicStroke(1.5f));
        g.drawLine(plotLeft, plotTop, plotLeft, plotBottom);
        g.drawLine(plotLeft, plotBottom, plotRight, plotBottom);

        int n = categories.size();
        double slot = (double) (plotRight - plotLeft) / n;

        if (bars) {
            int s = series.size();
            double groupW = slot * 0.7;
            double barW = groupW / s;
            for (int c = 0; c < n; c++) {
                double slotStart = plotLeft + c * slot + (slot - groupW) / 2;
                for (int si = 0; si < s; si++) {
                    Double v = valueAt(series.get(si), c);
                    if (v == null) continue;
                    int barH = (int) ((v / niceMax) * (plotBottom - plotTop));
                    barH = Math.max(0, barH);
                    int bx = (int) (slotStart + si * barW);
                    g.setColor(PALETTE[si % PALETTE.length]);
                    g.fillRect(bx, plotBottom - barH, (int) Math.max(1, barW - 2), barH);
                }
            }
        } else {
            g.setStroke(new BasicStroke(2.5f));
            for (int si = 0; si < series.size(); si++) {
                g.setColor(PALETTE[si % PALETTE.length]);
                int prevX = -1, prevY = -1;
                for (int c = 0; c < n; c++) {
                    Double v = valueAt(series.get(si), c);
                    if (v == null) { prevX = -1; continue; }
                    int x = (int) (plotLeft + c * slot + slot / 2);
                    int y = (int) (plotBottom - (v / niceMax) * (plotBottom - plotTop));
                    if (prevX >= 0) g.drawLine(prevX, prevY, x, y);
                    g.fillOval(x - 4, y - 4, 8, 8);
                    prevX = x; prevY = y;
                }
            }
        }

        // x category labels (drawn horizontally; keep them short at the source)
        g.setColor(TEXT);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        for (int c = 0; c < n; c++) {
            String lbl = categories.get(c);
            if (lbl == null) continue;
            int cx = (int) (plotLeft + c * slot + slot / 2);
            int lw = g.getFontMetrics().stringWidth(lbl);
            g.drawString(lbl, cx - lw / 2, plotBottom + 18);
        }

        if (legend) drawLegend(g, series.stream().map(ChartSeries::name).toList(), H - PAD + 4);
    }

    // --- pie -----------------------------------------------------------------

    private void drawPie(Graphics2D g, ChartSpec spec) {
        List<ChartSeries> series = nonEmptySeries(spec);
        List<String> categories = spec.categories() == null ? List.of() : spec.categories();
        if (series.isEmpty() || categories.isEmpty()) {
            drawEmpty(g);
            return;
        }
        ChartSeries first = series.get(0);
        double total = 0;
        for (int i = 0; i < categories.size(); i++) {
            Double v = valueAt(first, i);
            if (v != null && v > 0) total += v;
        }
        if (total <= 0) { drawEmpty(g); return; }

        int diameter = Math.min(H - TITLE_H - PAD * 2, 340);
        int px = PAD + 40;                                         // pie box left
        int py = TITLE_H + (H - TITLE_H - PAD - diameter) / 2;     // pie box top (below the title)
        double startAngle = 90; // start at top, go clockwise (negative extent)
        List<String> legend = new ArrayList<>();
        for (int i = 0; i < categories.size(); i++) {
            Double v = valueAt(first, i);
            if (v == null || v <= 0) continue;
            double extent = 360.0 * (v / total);
            g.setColor(PALETTE[i % PALETTE.length]);
            g.fill(new Arc2D.Double(px, py, diameter, diameter, startAngle, -extent, Arc2D.PIE));
            startAngle -= extent;
            String pct = String.format("%.0f%%", 100.0 * v / total);
            legend.add((categories.get(i) == null ? "?" : categories.get(i)) + "  " + pct);
        }
        // legend column on the right
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        int lx = px + diameter + 48;
        int ly = TITLE_H + 30;
        int li = 0;
        for (int i = 0; i < categories.size(); i++) {
            Double v = valueAt(first, i);
            if (v == null || v <= 0) continue;
            g.setColor(PALETTE[i % PALETTE.length]);
            g.fill(new Rectangle2D.Double(lx, ly - 12, 14, 14));
            g.setColor(TEXT);
            g.drawString(legend.get(li), lx + 22, ly);
            ly += 26;
            li++;
        }
    }

    // --- helpers -------------------------------------------------------------

    private void drawLegend(Graphics2D g, List<String> names, int y) {
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        int x = PAD + 56;
        for (int i = 0; i < names.size(); i++) {
            g.setColor(PALETTE[i % PALETTE.length]);
            g.fillRect(x, y - 11, 14, 14);
            g.setColor(TEXT);
            String name = names.get(i) == null ? "series " + (i + 1) : names.get(i);
            g.drawString(name, x + 20, y);
            x += 20 + g.getFontMetrics().stringWidth(name) + 28;
        }
    }

    private void drawEmpty(Graphics2D g) {
        g.setColor(TEXT);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 16));
        String msg = "No data";
        g.drawString(msg, (W - g.getFontMetrics().stringWidth(msg)) / 2, H / 2);
    }

    private static List<ChartSeries> nonEmptySeries(ChartSpec spec) {
        List<ChartSeries> out = new ArrayList<>();
        if (spec.series() == null) return out;
        for (ChartSeries s : spec.series()) {
            if (s != null && s.values() != null && !s.values().isEmpty()) out.add(s);
        }
        return out;
    }

    private static Double valueAt(ChartSeries s, int i) {
        List<Double> v = s.values();
        return (v != null && i < v.size()) ? v.get(i) : null;
    }

    private static double maxValue(List<ChartSeries> series) {
        double max = 0;
        for (ChartSeries s : series) {
            if (s.values() == null) continue;
            for (Double v : s.values()) if (v != null) max = Math.max(max, v);
        }
        return max;
    }

    /** Round an axis maximum up to a clean 1/2/5 × 10^n value so gridline labels read nicely. */
    private static double niceCeil(double v) {
        double exp = Math.floor(Math.log10(v));
        double base = Math.pow(10, exp);
        double f = v / base;
        double nice = f <= 1 ? 1 : f <= 2 ? 2 : f <= 5 ? 5 : 10;
        return nice * base;
    }

    private static String formatValue(double v, String unit) {
        String num;
        double abs = Math.abs(v);
        if (abs >= 1_000_000) num = String.format("%.1fM", v / 1_000_000);
        else if (abs >= 1_000) num = String.format("%.1fk", v / 1_000);
        else if (v == Math.rint(v)) num = String.format("%d", (long) v);
        else num = String.format("%.1f", v);
        return (unit == null || unit.isBlank()) ? num : num + unit;
    }

    private static byte[] toPng(BufferedImage img) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("chart PNG render failed", e);
        }
    }
}
