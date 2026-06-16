package dev.fedorov.ailife.mcp.icsimport.caldav;

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
 * Write-only CalDAV client for external ICS mirroring. Same protocol surface as
 * mcp-caldav's client (MKCOL → MKCALENDAR → PUT/DELETE) — kept duplicated here so the
 * two MCP servers can be deployed and version-bumped independently. If a third
 * caller appears, lift to a shared {@code libs/caldav-client} module.
 */
@Component
public class CalDavWriteClient {

    private static final Logger log = LoggerFactory.getLogger(CalDavWriteClient.class);
    private static final MediaType CALENDAR = MediaType.valueOf("text/calendar; charset=utf-8");
    private static final MediaType XML = MediaType.valueOf("application/xml; charset=utf-8");
    private static final HttpMethod MKCALENDAR = HttpMethod.valueOf("MKCALENDAR");
    private static final HttpMethod MKCOL = HttpMethod.valueOf("MKCOL");

    private static final String MKCALENDAR_BODY = """
            <?xml version="1.0" encoding="utf-8" ?>
            <C:mkcalendar xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
              <D:set>
                <D:prop>
                  <D:displayname>ai-life external</D:displayname>
                </D:prop>
              </D:set>
            </C:mkcalendar>
            """;

    private final WebClient http;
    private final Set<String> ensuredCollections = ConcurrentHashMap.newKeySet();

    public CalDavWriteClient(WebClient caldavWebClient) {
        this.http = caldavWebClient;
    }

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

    /** Best-effort collection removal; 404/405 are swallowed. */
    public void deleteCollection(UUID householdId, String calendar) {
        String path = "/" + householdId + "/" + calendar + "/";
        log.debug("DELETE {}", path);
        http.delete()
                .uri(path)
                .exchangeToMono(resp -> {
                    int code = resp.statusCode().value();
                    if (resp.statusCode().is2xxSuccessful() || code == 404 || code == 405) {
                        return resp.releaseBody().thenReturn(true);
                    }
                    return resp.bodyToMono(String.class).defaultIfEmpty("").map(body -> {
                        throw new IllegalStateException(
                                "CalDAV DELETE collection " + path + " failed: " + resp.statusCode() + " — " + body);
                    });
                })
                .block();
        ensuredCollections.remove(householdId + "/" + calendar);
    }

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
