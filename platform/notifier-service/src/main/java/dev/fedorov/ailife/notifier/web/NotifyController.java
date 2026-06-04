package dev.fedorov.ailife.notifier.web;

import dev.fedorov.ailife.contracts.notify.InternalSendRequest;
import dev.fedorov.ailife.contracts.notify.NotifyRequest;
import dev.fedorov.ailife.contracts.profile.UserDto;
import dev.fedorov.ailife.notifier.config.NotifierProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/v1/notify")
public class NotifyController {

    private static final Logger log = LoggerFactory.getLogger(NotifyController.class);

    private final WebClient profile;
    private final WebClient gateway;
    private final NotifierProperties props;

    public NotifyController(WebClient profileWebClient,
                            WebClient gatewayWebClient,
                            NotifierProperties props) {
        this.profile = profileWebClient;
        this.gateway = gatewayWebClient;
        this.props = props;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Void>> notify(@RequestBody NotifyRequest request) {
        if (request.userId() == null || request.text() == null || request.text().isBlank()) {
            return Mono.just(ResponseEntity.badRequest().build());
        }
        return profile.get()
                .uri("/v1/users/{id}", request.userId())
                .retrieve()
                .bodyToMono(UserDto.class)
                .flatMap(user -> {
                    if (user.telegramUserId() == null) {
                        log.warn("user {} has no telegram_user_id; cannot notify", user.id());
                        return Mono.just(ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).<Void>build());
                    }
                    return forwardToGateway(user.telegramUserId(), request.text());
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
