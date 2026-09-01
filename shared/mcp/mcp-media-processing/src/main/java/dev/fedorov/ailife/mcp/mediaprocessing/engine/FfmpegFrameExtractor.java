package dev.fedorov.ailife.mcp.mediaprocessing.engine;

import dev.fedorov.ailife.mcp.mediaprocessing.config.McpMediaProcessingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Default {@link FrameExtractor}: shells out to <b>ffmpeg</b> (the mature native tool bundled in the
 * image, same in-image posture as Tess4J's tesseract) to pull {@code n} evenly-spaced keyframes out of
 * a video. It probes the duration with {@code ffprobe}, then seeks to {@code duration*i/(n+1)} for each
 * {@code i} in {@code 1..n} and grabs a single JPEG frame — evenly spaced across the clip, deterministic,
 * no complex filtergraph. Selected by {@code mediaprocessing.frame-extractor=ffmpeg} (default).
 *
 * <p>Blocking ({@code ProcessBuilder} + a bounded {@code waitFor}) — the tool layer invokes it on a
 * blocking scheduler. Best-effort: unprobeable duration, a per-frame ffmpeg error, or a timeout drop
 * that frame (logged) rather than throwing; an empty result is the deterministic "no informative
 * visual" signal. Mirrors {@code YtDlpAudioFetchEngine}.
 */
@Component
@ConditionalOnProperty(name = "mediaprocessing.frame-extractor", havingValue = "ffmpeg", matchIfMissing = true)
public class FfmpegFrameExtractor implements FrameExtractor {

    private static final Logger log = LoggerFactory.getLogger(FfmpegFrameExtractor.class);

    private final McpMediaProcessingProperties props;

    public FfmpegFrameExtractor(McpMediaProcessingProperties props) {
        this.props = props;
    }

    @Override
    public List<byte[]> extract(byte[] bytes, String mimeType, int n) {
        if (bytes == null || bytes.length == 0 || n <= 0) {
            return List.of();
        }
        Path tmp = null;
        try {
            tmp = Files.createTempDirectory("frames-");
            Path video = tmp.resolve("input");
            Files.write(video, bytes);

            double duration = probeDuration(video);
            List<byte[]> frames = new ArrayList<>(n);
            for (int i = 1; i <= n; i++) {
                // Evenly spaced strictly inside the clip; when duration is unknown, seek to 0 so at
                // least the first frame is grabbed rather than nothing.
                double at = duration > 0 ? duration * i / (n + 1) : 0;
                Path out = tmp.resolve("frame-" + i + ".jpg");
                if (grabFrame(video, at, out)) {
                    frames.add(Files.readAllBytes(out));
                }
            }
            if (frames.isEmpty()) {
                log.warn("ffmpeg produced no frames ({} bytes, {})", bytes.length, mimeType);
            }
            return frames;
        } catch (Exception e) {
            log.warn("frame extraction failed ({} bytes, {}): {}", bytes.length, mimeType, e.toString());
            return List.of();
        } finally {
            deleteQuietly(tmp);
        }
    }

    /** ffprobe the container duration (seconds); {@code 0} when it can't be determined. */
    private double probeDuration(Path video) {
        try {
            List<String> cmd = List.of(
                    props.getFfprobeBin(),
                    "-v", "error",
                    "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1",
                    video.toString());
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            boolean finished = p.waitFor(props.getFrameTimeoutSec(), TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return 0;
            }
            return Double.parseDouble(out);
        } catch (Exception e) {
            return 0;
        }
    }

    /** Seek to {@code atSeconds} and write a single JPEG frame; {@code false} on any failure. */
    private boolean grabFrame(Path video, double atSeconds, Path out) {
        try {
            List<String> cmd = List.of(
                    props.getFfmpegBin(),
                    "-ss", String.format(java.util.Locale.ROOT, "%.3f", atSeconds),
                    "-i", video.toString(),
                    "-frames:v", "1",
                    "-q:v", "2",
                    "-y", out.toString());
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes(); // drain so the process can't block on a full pipe
            boolean finished = p.waitFor(props.getFrameTimeoutSec(), TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0 && Files.exists(out) && Files.size(out) > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort temp cleanup
                }
            });
        } catch (IOException ignored) {
            // best-effort temp cleanup
        }
    }
}
