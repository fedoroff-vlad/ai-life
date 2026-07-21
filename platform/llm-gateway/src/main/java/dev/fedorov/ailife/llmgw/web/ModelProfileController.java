package dev.fedorov.ailife.llmgw.web;

import dev.fedorov.ailife.llmgw.model.ModelProfileService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Workload-profile switch for the shared box (LC-4) — see {@link ModelProfileService}.
 *
 * <p><b>The whole controller is conditional</b>, so with {@code LLM_MODEL_PROFILE_ENABLED} unset
 * this path simply does not exist and ai-life runs exactly as it always has. The 404 is the honest
 * answer and the caller is built for it: coding-agent reads any non-2xx as "the downshift did not
 * happen" and declines to load its own model, which is the safe side of the failure.
 *
 * <p>Conversely a <b>2xx means the outgoing model has actually left Ollama</b>, not that the
 * request was accepted — the response is withheld until the eviction is confirmed. Callers size
 * their timeout for a model unload, not an HTTP round trip.
 *
 * <p>The request shape is deliberately local to this module rather than in {@code libs/contracts}:
 * the only caller is the coder contour in another repository, so there is no Java client to share
 * a DTO with.
 */
@RestController
@RequestMapping("/v1/model-profile")
@ConditionalOnProperty(name = "llm.model-profile.enabled", havingValue = "true")
public class ModelProfileController {

    /** {@code {"profile": "normal" | "coder-active"}}. */
    public record ModelProfileRequest(String profile) {}

    private final ModelProfileService profiles;

    public ModelProfileController(ModelProfileService profiles) {
        this.profiles = profiles;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, String>> switchProfile(@RequestBody ModelProfileRequest request) {
        return profiles.switchTo(request.profile()).then(Mono.fromSupplier(this::state));
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> current() {
        return state();
    }

    private Map<String, String> state() {
        return Map.of("profile", profiles.activeProfile(), "model", profiles.activeModel());
    }

    /**
     * A failed switch must never look like a successful one. {@code 503} says "ai-life is still on
     * the model it was on" — the caller's cue to stay off the shared GPU.
     */
    @ExceptionHandler(ModelProfileService.ModelSwitchException.class)
    public ResponseEntity<Map<String, String>> onSwitchFailure(ModelProfileService.ModelSwitchException ex) {
        HttpStatus status = switch (ex) {
            case ModelProfileService.UnknownProfileException ignored -> HttpStatus.BAD_REQUEST;
            case ModelProfileService.SwitchInProgressException ignored -> HttpStatus.CONFLICT;
            default -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        return ResponseEntity.status(status).body(Map.of(
                "error", ex.getMessage(),
                "profile", profiles.activeProfile(),
                "model", profiles.activeModel()));
    }
}
