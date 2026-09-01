package dev.fedorov.ailife.mcp.mediafetch.engine;

import dev.fedorov.ailife.mcp.mediafetch.config.McpMediaFetchProperties;
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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Default {@link AudioFetchEngine}: shells out to <b>yt-dlp</b> (the standalone binary bundled in the
 * image) to extract a media URL's <b>audio only</b> ({@code -x}, no video download), then reads the
 * bytes back so the caller can upload them to media-service. The no-captions STT path: whisper decodes
 * the container itself, so the audio codec is kept as-is (no {@code --audio-format} re-encode).
 * Selected by {@code media-fetch.audio-engine=yt-dlp} (default). ffmpeg (in the image) does the
 * container demux for {@code -x}.
 *
 * <p>Blocking ({@code ProcessBuilder} + a bounded {@code waitFor}) — callers invoke it on a blocking
 * scheduler. Best-effort: no audio stream, a yt-dlp error, or a timeout all yield
 * {@link ExtractedAudio#none()} (logged) rather than throwing. Mirrors {@link YtDlpTranscriptEngine}.
 */
@Component
@ConditionalOnProperty(name = "media-fetch.audio-engine", havingValue = "yt-dlp", matchIfMissing = true)
public class YtDlpAudioFetchEngine implements AudioFetchEngine {

    private static final Logger log = LoggerFactory.getLogger(YtDlpAudioFetchEngine.class);

    /** File extension → audio MIME, for the media-service upload content-type. */
    private static final Map<String, String> AUDIO_MIME = Map.of(
            "m4a", "audio/mp4",
            "mp3", "audio/mpeg",
            "opus", "audio/opus",
            "ogg", "audio/ogg",
            "webm", "audio/webm",
            "wav", "audio/wav",
            "aac", "audio/aac",
            "flac", "audio/flac");

    private final McpMediaFetchProperties props;

    public YtDlpAudioFetchEngine(McpMediaFetchProperties props) {
        this.props = props;
    }

    @Override
    public ExtractedAudio fetch(String url) {
        if (url == null || url.isBlank()) {
            return ExtractedAudio.none();
        }
        Path tmp = null;
        try {
            tmp = Files.createTempDirectory("ytaudio-");
            Path meta = tmp.resolve("meta.txt");
            List<String> cmd = List.of(
                    props.getYtDlpBin(),
                    "-x",
                    "--no-playlist", "--quiet", "--no-warnings",
                    "--print-to-file", "%(title)s\t%(duration)s\t%(extractor)s", meta.toString(),
                    "-o", tmp.resolve("%(id)s.%(ext)s").toString(),
                    url);

            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            boolean finished = p.waitFor(props.getAudioTimeoutSec(), TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                log.warn("yt-dlp (audio) timed out for {}", url);
                return ExtractedAudio.none();
            }

            Optional<Path> audio = findAudio(tmp, meta);
            if (audio.isEmpty()) {
                log.warn("no audio extracted for {}", url);
                return ExtractedAudio.none();
            }
            Path file = audio.get();
            byte[] bytes = Files.readAllBytes(file);
            String filename = file.getFileName().toString();
            return new ExtractedAudio(bytes, mimeFor(filename), filename,
                    metaField(meta, 0), metaField(meta, 2), duration(meta));
        } catch (Exception e) {
            log.warn("fetch_audio failed for {}: {}", url, e.toString());
            return ExtractedAudio.none();
        } finally {
            deleteQuietly(tmp);
        }
    }

    /** The extracted audio file — the single non-meta file yt-dlp wrote into the temp dir. */
    private static Optional<Path> findAudio(Path dir, Path meta) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(f -> !f.equals(meta))
                    .filter(Files::isRegularFile)
                    .sorted()
                    .findFirst();
        }
    }

    private static String mimeFor(String filename) {
        int dot = filename.lastIndexOf('.');
        String ext = dot >= 0 ? filename.substring(dot + 1).toLowerCase() : "";
        return AUDIO_MIME.getOrDefault(ext, "application/octet-stream");
    }

    /** {@code --print-to-file} wrote one tab-separated line: {@code title\tduration\textractor}. */
    private static String metaField(Path meta, int index) {
        try {
            if (!Files.exists(meta)) return null;
            String line = Files.readString(meta, StandardCharsets.UTF_8).strip();
            if (line.isEmpty()) return null;
            String[] parts = line.split("\t", -1);
            if (index >= parts.length) return null;
            String v = parts[index].strip();
            return (v.isEmpty() || "NA".equals(v)) ? null : v;
        } catch (IOException e) {
            return null;
        }
    }

    private static Integer duration(Path meta) {
        String d = metaField(meta, 1);
        if (d == null) return null;
        try {
            return (int) Math.round(Double.parseDouble(d));
        } catch (NumberFormatException e) {
            return null;
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
