package dev.fedorov.ailife.common.internalauth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Inbound guard for servlet (Spring MVC) services: rejects an {@code /internal/*} request that does
 * not carry the shared secret (#630). No-op when the guard is disabled (empty secret) or the path is
 * not internal — public and actuator endpoints are untouched.
 */
class InternalAuthServletFilter extends OncePerRequestFilter {

    private final InternalAuthProperties props;

    InternalAuthServletFilter(InternalAuthProperties props) {
        this.props = props;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (props.isEnabled() && InternalPaths.isInternal(request.getRequestURI())) {
            String auth = request.getHeader(HttpHeaders.AUTHORIZATION);
            if (!props.matches(auth)) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "internal auth required");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
