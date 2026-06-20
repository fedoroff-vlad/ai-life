package dev.fedorov.ailife.mcp.web.engine;

import dev.fedorov.ailife.contracts.web.VideoTranscript;
import dev.fedorov.ailife.mcp.web.config.McpWebProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Default {@link VideoTranscriptEngine}: shells out to <b>yt-dlp</b> (the standalone binary bundled
 * in the image — the same tool Agent-Reach uses) to download a video's subtitles / auto-captions as
 * WebVTT, then turns them into plain text with {@link SubtitleParser}. No download of the video
 * itself ({@code --skip-download}). Selected by {@code mcp-web.transcript-engine=yt-dlp} (default).
 *
 * <p>Blocking ({@code ProcessBuilder} + a bounded {@code waitFor}) — callers invoke it on a blocking
 * scheduler. Best-effort: a video without subtitles, a yt-dlp error, or a timeout all yield empty
 * text (logged) rather than throwing, so one bad video never sinks a research gather. Mirrors
 * {@code mcp-media-processing}'s native-in-image OCR engine.
 */
@Component
@ConditionalOnProperty(name = "mcp-web.transcript-engine", havingValue = "yt-dlp", matchIfMissing = true)
public class YtDlpTranscriptEngine implements VideoTranscriptEngine {

    private static final Logger log = LoggerFactory.getLogger(YtDlpTranscriptEngine.class);

    private final McpWebProperties props;

    public YtDlpTranscriptEngine(McpWebProperties props) {
        this.props = props;
    }

    @Override
    public VideoTranscript transcribe(String url, String lang) {
        if (url == null || url.isBlank()) {
            return empty(url);
        }
        Path tmp = null;
        try {
            tmp = Files.createTempDirectory("ytsub-");
            String langs = (lang != null && !lang.isBlank()) ? lang : props.getTranscriptLangs();
            List<String> cmd = List.of(
                    props.getYtDlpBin(),
                    "--skip-download", "--write-auto-subs", "--write-subs",
                    "--sub-langs", langs, "--sub-format", "vtt",
                    "--no-playlist", "--quiet", "--no-warnings",
                    "-o", tmp.resolve("%(id)s.%(ext)s").toString(),
                    url);

            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            boolean finished = p.waitFor(props.getTranscriptTimeoutSec(), TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                log.warn("yt-dlp timed out for {}", url);
            }

            Optional<Path> vtt = findVtt(tmp);
            if (vtt.isEmpty()) {
                log.warn("no subtitles found for {}", url);
                return empty(url);
            }
            String text = SubtitleParser.parseVtt(Files.readString(vtt.get(), StandardCharsets.UTF_8));
            int cap = props.getTranscriptMaxChars();
            boolean truncated = text.length() > cap;
            if (truncated) {
                text = text.substring(0, cap);
            }
            return new VideoTranscript(url, null, text, langFromFilename(vtt.get()), truncated);
        } catch (Exception e) {
            log.warn("transcribe_video failed for {}: {}", url, e.toString());
            return empty(url);
        } finally {
            deleteQuietly(tmp);
        }
    }

    private static Optional<Path> findVtt(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(f -> f.getFileName().toString().endsWith(".vtt")).sorted().findFirst();
        }
    }

    /** Filename is {@code <id>.<lang>.vtt} (e.g. {@code abc.en.vtt}) → the lang segment, best-effort. */
    private static String langFromFilename(Path vtt) {
        String name = vtt.getFileName().toString();
        String[] parts = name.split("\\.");
        return parts.length >= 3 ? parts[parts.length - 2] : null;
    }

    private static VideoTranscript empty(String url) {
        return new VideoTranscript(url, null, "", null, false);
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
