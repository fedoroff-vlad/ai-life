package dev.fedorov.ailife.notifier.web;

import dev.fedorov.ailife.contracts.notify.NotifyRequest;
import dev.fedorov.ailife.notifier.notify.NotifySender;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/v1/notify")
public class NotifyController {

    private final NotifySender sender;

    public NotifyController(NotifySender sender) {
        this.sender = sender;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Void>> notify(@RequestBody NotifyRequest request) {
        if (request.userId() == null || request.text() == null || request.text().isBlank()) {
            return Mono.just(ResponseEntity.badRequest().build());
        }
        return sender.send(request.userId(), request.text());
    }
}
