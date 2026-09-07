package dev.fedorov.ailife.common.internalauth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Shared-secret guard for inter-service {@code /internal/*} calls (#630, per
 * {@link <a href="../../../../../../../../plans/adr/ADR-0007-authorization-posture.md">ADR-0007</a>}).
 *
 * <p>The private single-box network is the primary trust boundary; this is the defense-in-depth layer
 * on top of it, so a caller that reaches the network still can't hit an internal tool without the
 * secret. One symmetric secret shared by all first-party services — it authenticates "a first-party
 * caller", not <em>which</em> one (an accepted limit for the single-box target; see ADR-0007).
 *
 * <p><b>Empty (the default) = disabled</b> — the inbound filters and the outbound header are both
 * no-ops, so dev/CI and the not-yet-live deploy behave exactly as before. Set {@code INTERNAL_SHARED_SECRET}
 * (relaxed-binds to {@code internal.shared-secret}) on every service to turn it on; all first-party
 * services must share the same value.
 */
@ConfigurationProperties(prefix = "internal")
public class InternalAuthProperties {

    /** The shared bearer secret. Blank = guard disabled (allow all), for back-compat. */
    private String sharedSecret = "";

    public String getSharedSecret() {
        return sharedSecret;
    }

    public void setSharedSecret(String sharedSecret) {
        this.sharedSecret = sharedSecret;
    }

    /** Whether the guard is active (a non-blank secret was configured). */
    public boolean isEnabled() {
        return sharedSecret != null && !sharedSecret.isBlank();
    }

    /** The exact {@code Authorization} header value an authorized caller must present. */
    public String expectedHeader() {
        return "Bearer " + sharedSecret;
    }

    /** Constant-time check that an inbound {@code Authorization} header carries the shared secret. */
    public boolean matches(String authHeader) {
        return authHeader != null && constantTimeEquals(authHeader, expectedHeader());
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }
}
