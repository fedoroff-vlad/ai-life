package dev.fedorov.ailife.mcp.mediaprocessing.engine;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.media.TranscriptResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

/**
 * Real STT via an OSS whisper ASR sidecar (e.g. {@code onerahmet/openai-whisper-asr-webservice},
 * faster-whisper engine) reached over HTTP — engine decision LOCKED = "whisper sidecar service"
 * (owner 2026-06-21, mirror of the Tess4J-in-image OCR lock but polyglot-by-design: the model
 * runs in its own container, the JVM image stays slim). This is the deployed default;
 * {@link StubSttEngine} is selected only when {@code mediaprocessing.stt-engine=stub}.
 *
 * <p>Posts the bytes as multipart {@code audio_file} to {@code POST /asr?output=json} and reads
 * the {@code text}/{@code language} fields. Unreadable/no-speech audio yields empty text (not an
 * error — the sidecar still returns 200); a genuine sidecar failure (5xx / timeout / unparseable
 * body) surfaces as {@link IllegalStateException}, matching the {@link OcrEngine} contract.
 */
@Component
@ConditionalOnProperty(name = "mediaprocessing.stt-engine", havingValue = "whisper", matchIfMissing = true)
public class WhisperSttEngine implements SttEngine {

    private static final Logger log = LoggerFactory.getLogger(WhisperSttEngine.class);
    private static final Duration TIMEOUT = Duration.ofMinutes(2);

    private final WebClient http;
    private final ObjectMapper json;

    public WhisperSttEngine(WebClient whisperWebClient, ObjectMapper json) {
        this.http = whisperWebClient;
        this.json = json;
        log.info("WhisperSttEngine ready (sidecar over HTTP)");
    }

    @Override
    public TranscriptResult transcribe(byte[] bytes, String mimeType) {
        if (bytes == null || bytes.length == 0) {
            return new TranscriptResult("", null, null);
        }
        MediaType type = parseType(mimeType);
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("audio_file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return "audio";
            }
        }).contentType(type);

        String response;
        try {
            response = http.post()
                    .uri(uri -> uri.path("/asr")
                            .queryParam("task", "transcribe")
                            .queryParam("encode", "true")
                            .queryParam("output", "json")
                            .build())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(body.build()))
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(TIMEOUT)
                    .block();
        } catch (Exception e) {
            // Sidecar down / slow / non-2xx — a genuine engine failure, surface it.
            throw new IllegalStateException("STT failed: " + e.getMessage(), e);
        }
        return parse(response);
    }

    private TranscriptResult parse(String response) {
        if (response == null || response.isBlank()) {
            return new TranscriptResult("", null, null);
        }
        try {
            JsonNode root = json.readTree(response);
            String text = root.path("text").asText("").strip();
            String lang = root.hasNonNull("language") ? root.get("language").asText() : null;
            Double duration = durationFromSegments(root);
            return new TranscriptResult(text, lang, duration);
        } catch (Exception e) {
            // A 200 with a body we can't read is still a genuine engine failure.
            throw new IllegalStateException("STT response unparseable: " + e.getMessage(), e);
        }
    }

    /** Whisper's last segment {@code end} is the clip duration; absent in some engines. */
    private Double durationFromSegments(JsonNode root) {
        JsonNode segments = root.path("segments");
        if (segments.isArray() && !segments.isEmpty()) {
            JsonNode end = segments.get(segments.size() - 1).path("end");
            if (end.isNumber()) {
                return end.asDouble();
            }
        }
        return null;
    }

    private MediaType parseType(String mimeType) {
        if (mimeType == null || mimeType.isBlank()) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        try {
            return MediaType.parseMediaType(mimeType);
        } catch (Exception e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
