package dev.fedorov.ailife.notifier.notify;

import dev.fedorov.ailife.contracts.notify.InternalSendRequest;
import dev.fedorov.ailife.contracts.profile.UserDto;
import dev.fedorov.ailife.notifier.config.NotifierProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Resolves a {@code userId} to its {@code telegram_user_id} (via profile-service) and
 * forwards the text to gateway-telegram's {@code /internal/send}. Shared by the
 * synchronous {@code POST /v1/notify} controller and the async bus consumer.
 *
 * <p>Outcome encoding (mirrors {@code /v1/notify} status codes):
 * <ul>
 *   <li>{@code 202 ACCEPTED} — delivered.</li>
 *   <li>{@code 404 NOT_FOUND} — no such user (permanent).</li>
 *   <li>{@code 422 UNPROCESSABLE_ENTITY} — user has no telegram link yet (permanent).</li>
 * </ul>
 * Any other failure (profile/gateway 5xx, timeout, network) is propagated as an error so
 * callers can decide retry policy — the bus consumer leaves the outbox row PENDING.
 */
@Component
public class NotifySender {

    private static final Logger log = LoggerFactory.getLogger(NotifySender.class);

    private final WebClient profile;
    private final WebClient gateway;
    private final NotifierProperties props;

    public NotifySender(WebClient profileWebClient,
                        WebClient gatewayWebClient,
                        NotifierProperties props) {
        this.profile = profileWebClient;
        this.gateway = gatewayWebClient;
        this.props = props;
    }

    public Mono<ResponseEntity<Void>> send(UUID userId, String text) {
        return profile.get()
                .uri("/v1/users/{id}", userId)
                .retrieve()
                .bodyToMono(UserDto.class)
                .flatMap(user -> {
                    if (user.telegramUserId() == null) {
                        log.warn("user {} has no telegram_user_id; cannot notify", user.id());
                        return Mono.just(ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).<Void>build());
                    }
                    return forwardToGateway(user.telegramUserId(), text);
                })
                .onErrorResume(WebClientResponseException.NotFound.class,
                        ex -> Mono.just(ResponseEntity.notFound().build()));
    }

    private Mono<ResponseEntity<Void>> forwardToGateway(long telegramUserId, String text) {
        return gateway.post()
                .uri("/internal/send")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + props.getInternalApiToken())
                .bodyValue(new InternalSendRequest(telegramUserId, text))
                .retrieve()
                .toBodilessEntity()
                .map(r -> ResponseEntity.accepted().<Void>build());
    }
}
