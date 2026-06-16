package dev.fedorov.ailife.mcp.caldav.caldav;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thin write-only CalDAV client to Radicale. We use:
 * <ul>
 *   <li><strong>MKCALENDAR</strong> to create the collection on first use, idempotent
 *       (a 405 / 409 means "already exists" — fine).</li>
 *   <li><strong>PUT</strong> to create-or-replace a single VEVENT at
 *       {@code /<household>/<calendar>/<uid>.ics}.</li>
 *   <li><strong>DELETE</strong> for removal of the same path.</li>
 * </ul>
 * Reads in this PR go straight to the {@code events_cache} table. Full CalDAV
 * REPORT/PROPFIND will land with PR10 (external ICS subscriptions).
 */
@Component
public class CalDavClient {

    private static final Logger log = LoggerFactory.getLogger(CalDavClient.class);
    private static final MediaType CALENDAR = MediaType.valueOf("text/calendar; charset=utf-8");
    private static final MediaType XML = MediaType.valueOf("application/xml; charset=utf-8");
    private static final HttpMethod MKCALENDAR = HttpMethod.valueOf("MKCALENDAR");
    private static final HttpMethod MKCOL = HttpMethod.valueOf("MKCOL");

    /**
     * Minimal MKCALENDAR body. Radicale ignores most of it but requires well-formed XML.
     */
    private static final String MKCALENDAR_BODY = """
            <?xml version="1.0" encoding="utf-8" ?>
            <C:mkcalendar xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
              <D:set>
                <D:prop>
                  <D:displayname>ai-life</D:displayname>
                </D:prop>
              </D:set>
            </C:mkcalendar>
            """;

    private final WebClient http;
    private final Set<String> ensuredCollections = ConcurrentHashMap.newKeySet();

    public CalDavClient(WebClient caldavWebClient) {
        this.http = caldavWebClient;
    }

    /**
     * @return the ETag the server assigned to this event (may be {@code null} if Radicale
     *         did not echo one — Radicale's behaviour varies by version).
     */
    public String putEvent(UUID householdId, String calendar, String uid, String icsBody) {
        ensureCollection(householdId, calendar);
        String path = pathFor(householdId, calendar, uid);
        log.debug("PUT {} ({} bytes)", path, icsBody.length());
        return http.put()
                .uri(path)
                .contentType(CALENDAR)
                .bodyValue(icsBody)
                .exchangeToMono(resp -> resp.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(body -> {
                            if (resp.statusCode().is2xxSuccessful()) {
                                return resp.headers().header(HttpHeaders.ETAG).stream()
                                        .findFirst().orElse(null);
                            }
                            throw new IllegalStateException(
                                    "CalDAV PUT " + path + " failed: " + resp.statusCode() + " — " + body);
                        }))
                .block();
    }

    public void deleteEvent(UUID householdId, String calendar, String uid) {
        String path = pathFor(householdId, calendar, uid);
        log.debug("DELETE {}", path);
        http.delete()
                .uri(path)
                .exchangeToMono(resp -> {
                    if (resp.statusCode().is2xxSuccessful() || resp.statusCode().value() == 404) {
                        return resp.releaseBody().thenReturn(true);
                    }
                    return resp.bodyToMono(String.class).defaultIfEmpty("").map(body -> {
                        throw new IllegalStateException(
                                "CalDAV DELETE " + path + " failed: " + resp.statusCode() + " — " + body);
                    });
                })
                .block();
    }

    /**
     * Ensure the principal collection ({@code /{household}/}) and the calendar collection
     * ({@code /{household}/{calendar}/}) exist. Radicale's URL hierarchy mandates the
     * principal segment as a real collection — without it a child MKCALENDAR returns 409
     * "Conflict in the request". Idempotent: 405 (already exists) and 409 are swallowed.
     */
    private void ensureCollection(UUID householdId, String calendar) {
        String key = householdId + "/" + calendar;
        if (ensuredCollections.contains(key)) {
            return;
        }
        mkcol("/" + householdId + "/", MKCOL, null, null);
        mkcol("/" + householdId + "/" + calendar + "/", MKCALENDAR, XML, MKCALENDAR_BODY);
        ensuredCollections.add(key);
    }

    private void mkcol(String path, HttpMethod method, MediaType contentType, String body) {
        log.debug("{} {}", method, path);
        WebClient.RequestBodySpec spec = http.method(method).uri(path);
        WebClient.RequestHeadersSpec<?> ready = (contentType != null && body != null)
                ? spec.contentType(contentType).bodyValue(body)
                : spec;
        ready.exchangeToMono(resp -> resp.bodyToMono(String.class).defaultIfEmpty("")
                        .map(b -> {
                            int code = resp.statusCode().value();
                            log.debug("{} {} → {}", method, path, code);
                            if (resp.statusCode().is2xxSuccessful() || code == 405 || code == 409) {
                                return true;
                            }
                            throw new IllegalStateException(
                                    method + " " + path + " failed: " + resp.statusCode() + " — " + b);
                        }))
                .block();
    }

    private static String pathFor(UUID householdId, String calendar, String uid) {
        return "/" + householdId + "/" + calendar + "/" + uid + ".ics";
    }
}
