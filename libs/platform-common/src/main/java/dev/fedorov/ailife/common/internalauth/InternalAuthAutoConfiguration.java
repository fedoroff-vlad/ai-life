package dev.fedorov.ailife.common.internalauth;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.webclient.WebClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;

/**
 * Wires the shared-secret {@code /internal/*} guard (#630, ADR-0007) into any service that has
 * platform-common on the classpath — no per-service code. Three pieces, each conditional so it loads
 * only where it applies, and all no-ops when {@link InternalAuthProperties#isEnabled()} is false
 * (empty secret = disabled, the default):
 *
 * <ul>
 *   <li><b>Outbound</b> — a {@link WebClientCustomizer} adds {@code Authorization: Bearer <secret>} to
 *       every outbound request whose path is {@code /internal/*}. It rides the auto-configured
 *       {@code WebClient.Builder} (which all consumers {@code .clone()}), so it covers every client
 *       centrally; a non-internal call (e.g. to llm-gateway {@code /v1/chat}) is left untouched.</li>
 *   <li><b>Inbound (servlet)</b> — an {@link InternalAuthServletFilter} for Spring MVC services.</li>
 *   <li><b>Inbound (reactive)</b> — an {@link InternalAuthWebFilter} for WebFlux services.</li>
 * </ul>
 */
@AutoConfiguration
@EnableConfigurationProperties(InternalAuthProperties.class)
public class InternalAuthAutoConfiguration {

    /** Outbound: stamp the secret onto {@code /internal/*} requests of every WebClient. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(WebClientCustomizer.class)
    static class Outbound {

        @Bean
        WebClientCustomizer internalAuthWebClientCustomizer(InternalAuthProperties props) {
            ExchangeFilterFunction filter = ExchangeFilterFunction.ofRequestProcessor(request -> {
                if (props.isEnabled() && InternalPaths.isInternal(request.url().getPath())) {
                    return reactor.core.publisher.Mono.just(ClientRequest.from(request)
                            .headers(h -> h.setBearerAuth(props.getSharedSecret()))
                            .build());
                }
                return reactor.core.publisher.Mono.just(request);
            });
            return builder -> builder.filter(filter);
        }
    }

    /** Inbound guard for servlet (Spring MVC) services. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(name = "org.springframework.web.filter.OncePerRequestFilter")
    static class Servlet {

        @Bean
        FilterRegistrationBean<InternalAuthServletFilter> internalAuthServletFilter(
                InternalAuthProperties props) {
            FilterRegistrationBean<InternalAuthServletFilter> reg =
                    new FilterRegistrationBean<>(new InternalAuthServletFilter(props));
            reg.addUrlPatterns("/internal/*");
            reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
            return reg;
        }
    }

    /** Inbound guard for reactive (WebFlux) services. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
    @ConditionalOnClass(name = "org.springframework.web.server.WebFilter")
    static class Reactive {

        @Bean
        InternalAuthWebFilter internalAuthWebFilter(InternalAuthProperties props) {
            return new InternalAuthWebFilter(props);
        }
    }
}
