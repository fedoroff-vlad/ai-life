package dev.fedorov.ailife.common.internalauth;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Inbound guard for reactive (WebFlux) services: the WebFilter twin of {@link InternalAuthServletFilter}
 * (#630). Rejects an {@code /internal/*} request without the shared secret; no-op when disabled or the
 * path is not internal.
 */
class InternalAuthWebFilter implements WebFilter {

    private final InternalAuthProperties props;

    InternalAuthWebFilter(InternalAuthProperties props) {
        this.props = props;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (props.isEnabled()
                && InternalPaths.isInternal(exchange.getRequest().getPath().value())) {
            String auth = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
            if (!props.matches(auth)) {
                exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                return exchange.getResponse().setComplete();
            }
        }
        return chain.filter(exchange);
    }
}
