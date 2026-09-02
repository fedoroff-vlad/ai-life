package dev.fedorov.ailife.agents.researcher.flow;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.agentruntime.coordinate.Coordinator;
import dev.fedorov.ailife.agentruntime.coordinate.UntrustedContent;
import dev.fedorov.ailife.agentruntime.http.CaptionClient;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.researcher.config.ResearcherAgentProperties;
import dev.fedorov.ailife.agents.researcher.http.MediaFetchClient;
import dev.fedorov.ailife.agents.researcher.http.MediaProcessingClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.Attachment;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.media.FramesResult;
import dev.fedorov.ailife.contracts.media.TranscriptResult;
import dev.fedorov.ailife.contracts.mediafetch.AudioFetchResult;
import dev.fedorov.ailife.contracts.mediafetch.VideoTranscript;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The multimodal video-understanding flow (V-c): turn any video source — a platform <b>link</b>
 * (YouTube / Instagram / TikTok / Threads / …) or an uploaded video <b>file</b> — into one "о чём это
 * видео" answer. Cheap-first, three tiers, each soft-failing to the next (token-economy doctrine,
 * research.md §Video understanding):
 * <ol>
 *   <li><b>captions</b> (link only) — {@code mcp-media-fetch transcribe_video}: a video's subtitles,
 *       no download, no model — the cheapest read; empty → next.</li>
 *   <li><b>speech</b> — {@code fetch_audio} (link) → id, then {@code mcp-media-processing transcribe}
 *       (STT). A file is already a media id, so it starts here. Empty/no-speech → next.</li>
 *   <li><b>visual</b> (file only) — {@code frames} (MP-e keyframes) → {@code caption} each (shared
 *       {@link CaptionClient}) → a scene description, for speechless video (ASMR / landscape).</li>
 * </ol>
 * Then <b>one</b> LLM synthesis on the shared {@link Coordinator} from {@code [GUARD, AGENT.md, video
 * SKILL.md] + {payload, {video: scene}}}. The transcript/caption is attacker-controlled, so
 * {@link UntrustedContent#GUARD} leads (injection guard, #599). A domain-less video drop routes here (see
 * {@link #detect}); everything else stays with {@code Researcher}.
 *
 * <p><b>Link visual tier is not available:</b> {@code fetch_audio} pulls audio only, so a captionless,
 * speechless <i>link</i> has no video bytes to frame (a {@code fetch_video} acquisition tool is future
 * work). A speechless video <i>file</i> reaches the visual tier normally.
 */
@Component
public class VideoUnderstanding {

    private static final Logger log = LoggerFactory.getLogger(VideoUnderstanding.class);
    private static final String SKILL_NAME = "video";
    private static final String FRAME_INSTRUCTION =
            "Describe what this single video frame shows, in one concise sentence.";
    private static final Pattern URL = Pattern.compile("https?://\\S+");

    /** Hosts whose links are a specific video to understand (not a topic to research). */
    private static final Set<String> VIDEO_HOSTS = Set.of(
            "youtube.com", "youtu.be", "vimeo.com", "tiktok.com", "instagram.com",
            "threads.net", "threads.com", "rutube.ru", "dailymotion.com", "facebook.com/watch");

    private final Coordinator coordinator;
    private final MediaFetchClient mediaFetch;
    private final MediaProcessingClient mediaProcessing;
    private final CaptionClient caption;
    private final SkillRegistry skills;
    private final AgentManifest manifest;
    private final ObjectMapper json;
    private final ResearcherAgentProperties props;

    public VideoUnderstanding(Coordinator coordinator,
                              MediaFetchClient mediaFetch,
                              MediaProcessingClient mediaProcessing,
                              CaptionClient caption,
                              SkillRegistry skills,
                              AgentManifest manifest,
                              ObjectMapper json,
                              ResearcherAgentProperties props) {
        this.coordinator = coordinator;
        this.mediaFetch = mediaFetch;
        this.mediaProcessing = mediaProcessing;
        this.caption = caption;
        this.skills = skills;
        this.manifest = manifest;
        this.json = json;
        this.props = props;
    }

    public Mono<VideoResult> understand(NormalizedMessage msg) {
        Optional<VideoSource> maybe = detect(msg);
        if (maybe.isEmpty()) {
            return Mono.just(new VideoResult(
                    "Пришлите ссылку на видео или видео-файл — я расскажу, о чём оно.", null));
        }
        VideoSource src = maybe.get();
        UUID household = msg.householdId();
        UUID owner = msg.userId();
        return gatherScene(src, household, owner)
                .flatMap(scene -> synthesize(msg.text(), src, scene))
                .onErrorResume(e -> {
                    log.warn("video understanding failed for {}: {}", src, e.toString());
                    return Mono.just(new VideoResult(
                            "Не смог разобрать видео. Попробуйте позже.", null));
                });
    }

    /** Run the cheap-first tiers, returning the first non-empty scene (or {@link Scene#EMPTY}). */
    private Mono<Scene> gatherScene(VideoSource src, UUID household, UUID owner) {
        if (src.isLink()) {
            return captionsTier(src.url())
                    .flatMap(scene -> scene.present()
                            ? Mono.just(scene)
                            : speechFromLinkTier(src.url(), household, owner));
        }
        // File: already a media id — start at STT, fall through to the visual tier.
        return speechTier(src.mediaId())
                .flatMap(scene -> scene.present()
                        ? Mono.just(scene)
                        : visualTier(src.mediaId(), household, owner));
    }

    /** Tier 1 (link): captions/subtitles — the cheapest content read. */
    private Mono<Scene> captionsTier(String url) {
        return mediaFetch.transcribeVideo(url, null)
                .map(vt -> nonBlank(vt.text())
                        ? new Scene("captions", vt.text(), title(vt))
                        : Scene.EMPTY)
                .onErrorResume(e -> {
                    log.warn("transcribe_video failed for {}: {}", url, e.toString());
                    return Mono.just(Scene.EMPTY);
                });
    }

    /** Tier 2 (link): fetch_audio → media id → STT. */
    private Mono<Scene> speechFromLinkTier(String url, UUID household, UUID owner) {
        return mediaFetch.fetchAudio(url, household, owner)
                .onErrorResume(e -> {
                    log.warn("fetch_audio failed for {}: {}", url, e.toString());
                    return Mono.just(AudioFetchResult.empty());
                })
                .flatMap(af -> af.mediaId() == null
                        ? Mono.just(Scene.EMPTY)
                        : speechTier(af.mediaId()));
    }

    /** Tier 2 (shared): STT on a stored audio/video media id. */
    private Mono<Scene> speechTier(String mediaId) {
        return mediaProcessing.transcribe(mediaId)
                .map(tr -> nonBlank(tr.text())
                        ? new Scene("speech", tr.text(), null)
                        : Scene.EMPTY)
                .onErrorResume(e -> {
                    log.warn("transcribe failed for {}: {}", mediaId, e.toString());
                    return Mono.just(Scene.EMPTY);
                });
    }

    /** Tier 3 (file): keyframes → caption each → a joined scene description. */
    private Mono<Scene> visualTier(String mediaId, UUID household, UUID owner) {
        return mediaProcessing.frames(mediaId, props.getVideoFrames(), household, owner)
                .onErrorResume(e -> {
                    log.warn("frames failed for {}: {}", mediaId, e.toString());
                    return Mono.just(FramesResult.empty());
                })
                .flatMap(fr -> {
                    List<String> ids = fr.frameMediaIds() == null ? List.of() : fr.frameMediaIds();
                    if (ids.isEmpty()) {
                        return Mono.just(Scene.EMPTY);
                    }
                    return Flux.fromIterable(ids)
                            .concatMap(fid -> caption.caption(fid, FRAME_INSTRUCTION)
                                    .map(r -> r.text() == null ? "" : r.text())
                                    .onErrorResume(e -> {
                                        log.warn("caption failed for frame {}: {}", fid, e.toString());
                                        return Mono.empty();
                                    })
                                    .filter(VideoUnderstanding::nonBlank))
                            .collectList()
                            .map(caps -> caps.isEmpty()
                                    ? Scene.EMPTY
                                    : new Scene("visual", String.join("\n", caps), null));
                });
    }

    /** Fold the extracted scene into a corpus and synthesize one grounded answer. */
    private Mono<VideoResult> synthesize(String userText, VideoSource src, Scene scene) {
        if (!scene.present()) {
            return Mono.just(new VideoResult(
                    "Не удалось извлечь содержимое видео (нет субтитров, речи или кадров). "
                            + "Пришлите другой файл или ссылку.", null));
        }
        ObjectNode video = json.createObjectNode();
        video.put("channel", scene.source());          // captions | speech | visual
        // The content is a single untrusted blob (a whole transcript / joined frame captions), not one
        // field among a self-labeling structure — so reinforce GUARD with an explicit fence around it so
        // the model sees exactly where the untrusted span starts and ends (#599; the fence is the
        // documented reinforcement for a lone untrusted value).
        video.put("content", UntrustedContent.fence("video-" + scene.source(), scene.text()));
        if (scene.title() != null) video.put("title", scene.title());
        video.put("origin", src.isLink() ? src.url() : "uploaded file");

        ObjectNode payload = json.createObjectNode();
        payload.put("userText", userText == null ? "" : userText);

        Map<String, Mono<tools.jackson.databind.JsonNode>> gather =
                Map.of("video", Mono.just(video));

        // The scene content is attacker-controlled (a video can narrate/print "ignore your
        // instructions"). Frame it as untrusted data — GUARD leads so the rule is set before the data
        // (plans/architecture.md §Security; mirrors the research/OCR flows). #599.
        return coordinator.coordinate(
                        List.of(UntrustedContent.GUARD, manifest.body(), skillBody()),
                        payload,
                        gather,
                        LlmChannel.DEFAULT)
                .map(r -> new VideoResult(r.text(), r.llmModel()))
                .onErrorResume(e -> {
                    log.warn("video synthesis failed: {}", e.toString());
                    return Mono.just(new VideoResult(
                            "Разобрал видео, но не смог собрать ответ. Попробуйте позже.", null));
                });
    }

    /**
     * Decide whether a message is a specific video to understand (vs a research topic): a video-file
     * attachment, or a video-host URL in the text. Returns empty for everything else, so the
     * {@code IntentController} falls back to the {@code Researcher} flow.
     */
    public static Optional<VideoSource> detect(NormalizedMessage msg) {
        if (msg == null) {
            return Optional.empty();
        }
        List<Attachment> atts = msg.attachments() == null ? List.of() : msg.attachments();
        for (Attachment a : atts) {
            if (a.mimeType() != null && a.mimeType().toLowerCase(Locale.ROOT).startsWith("video/")
                    && a.storageUri() != null && !a.storageUri().isBlank()) {
                return Optional.of(new VideoSource(null, a.storageUri()));
            }
        }
        String text = msg.text();
        if (text != null && !text.isBlank()) {
            Matcher m = URL.matcher(text);
            while (m.find()) {
                String u = m.group();
                if (isVideoHost(u)) {
                    return Optional.of(new VideoSource(stripTrailing(u), null));
                }
            }
        }
        return Optional.empty();
    }

    private static boolean isVideoHost(String url) {
        String u = url.toLowerCase(Locale.ROOT);
        return VIDEO_HOSTS.stream().anyMatch(u::contains);
    }

    /** Drop trailing punctuation a user often leaves around a pasted link. */
    private static String stripTrailing(String url) {
        int end = url.length();
        while (end > 0 && ".,;:!?)\"'".indexOf(url.charAt(end - 1)) >= 0) {
            end--;
        }
        return url.substring(0, end);
    }

    private static boolean nonBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String title(VideoTranscript vt) {
        return nonBlank(vt.title()) ? vt.title() : null;
    }

    private String skillBody() {
        return skills.all().stream()
                .filter(s -> SKILL_NAME.equals(s.name()))
                .map(Skill::body)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "video SKILL.md not loaded — check skills-classpath"));
    }

    /** A video to understand: exactly one of {@code url} (link) or {@code mediaId} (uploaded file). */
    public record VideoSource(String url, String mediaId) {
        public boolean isLink() {
            return url != null;
        }
    }

    /** The extracted understanding of one channel. {@code source} ∈ captions | speech | visual. */
    private record Scene(String source, String text, String title) {
        static final Scene EMPTY = new Scene(null, null, null);

        boolean present() {
            return source != null && nonBlank(text);
        }
    }

    /** The synthesized answer plus the model that produced it (for the response contract). */
    public record VideoResult(String text, String model) {
    }
}
