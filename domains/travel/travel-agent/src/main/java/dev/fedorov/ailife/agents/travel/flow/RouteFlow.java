package dev.fedorov.ailife.agents.travel.flow;

import dev.fedorov.ailife.agentruntime.deliver.DeliverablePublisher;
import dev.fedorov.ailife.agentruntime.transparency.DegradedNotice;
import dev.fedorov.ailife.agents.travel.http.MediaFetchClient;
import dev.fedorov.ailife.agents.travel.http.RouteImportClient;
import dev.fedorov.ailife.agents.travel.http.TripWalletClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.Attachment;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.travel.ImportRouteInput;
import dev.fedorov.ailife.contracts.travel.RouteDto;
import dev.fedorov.ailife.contracts.travel.TripDto;
import dev.fedorov.ailife.docrender.Doc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The route-import flow (RT-c): the owner sends a route/itinerary file (GPX/GeoJSON/KML/KMZ) → fetch the
 * bytes from media-service → <b>sniff the format from the content</b> (no filename needed) → import it via
 * {@code mcp-travel} (RT-a), attaching it to the household's <b>active trip</b> when there is one → render a
 * route board (point count, distance, a map deep link built from the first point) via the shared
 * {@link DeliverablePublisher} and reply. The agent parses owner-supplied bytes only — it never fetches a
 * remote map or transmits the file anywhere; the map link is a plain OpenStreetMap URL, not an API call.
 */
@Component
public class RouteFlow {

    private static final Logger log = LoggerFactory.getLogger(RouteFlow.class);
    private static final BigDecimal METRES_PER_KM = new BigDecimal(1000);

    private static final String UNKNOWN_FILE =
            "Не смог распознать формат маршрута. Поддерживаю GPX, GeoJSON, KML и KMZ.";
    private static final String FILE_REJECT =
            "Файл получен, но маршрут не распознан — похоже, он пустой или не в том формате.";
    private static final String LINK_REJECT =
            "Не смог разобрать ссылку на карту. Если это короткая ссылка (goo.gl или yandex.ru/-/…), "
            + "пришлите файл маршрута (GPX/KML) — его прочитаю точно.";

    private final MediaFetchClient media;
    private final RouteImportClient routes;
    private final TripWalletClient wallet;
    private final DeliverablePublisher publisher;
    private final AgentManifest manifest;

    public RouteFlow(MediaFetchClient media, RouteImportClient routes, TripWalletClient wallet,
                     DeliverablePublisher publisher, AgentManifest manifest) {
        this.media = media;
        this.routes = routes;
        this.wallet = wallet;
        this.publisher = publisher;
        this.manifest = manifest;
    }

    /** File-attachment import (RT-c): fetch the bytes → sniff the format → import to the active trip. */
    public Mono<IntentResponse> handle(NormalizedMessage msg, Attachment attachment) {
        return media.fetch(attachment.storageUri())
                .flatMap(bytes -> importFromBytes(msg, bytes))
                .onErrorResume(e -> {
                    log.warn("route fetch/import failed: {}", e.toString());
                    return Mono.just(reply("Не удалось импортировать файл маршрута. Попробуйте позже."));
                });
    }

    /** Map-link import (RT-d2): a map URL in the message text → import its coordinates to the active trip. */
    public Mono<IntentResponse> handleLink(NormalizedMessage msg, String url) {
        return attachToActiveTrip(msg, "maplink", url, LINK_REJECT)
                .onErrorResume(e -> {
                    log.warn("route link import failed: {}", e.toString());
                    return Mono.just(reply(LINK_REJECT));
                });
    }

    private Mono<IntentResponse> importFromBytes(NormalizedMessage msg, byte[] bytes) {
        String format = sniffFormat(bytes);
        if (format == null) {
            return Mono.just(reply(UNKNOWN_FILE));
        }
        String content = "kmz".equals(format)
                ? Base64.getEncoder().encodeToString(bytes)
                : new String(bytes, StandardCharsets.UTF_8);
        return attachToActiveTrip(msg, format, content, FILE_REJECT);
    }

    /** Import {@code content} as {@code format}, attaching to the household's active trip when there is one. */
    private Mono<IntentResponse> attachToActiveTrip(NormalizedMessage msg, String format, String content,
                                                    String rejectMsg) {
        return wallet.getActiveTrip(msg.householdId())
                .flatMap(trip -> importAndReply(msg, format, content, trip, rejectMsg))
                .switchIfEmpty(Mono.defer(() -> importAndReply(msg, format, content, null, rejectMsg)));
    }

    private Mono<IntentResponse> importAndReply(NormalizedMessage msg, String format, String content,
                                                TripDto trip, String rejectMsg) {
        UUID tripId = trip == null ? null : trip.id();
        ImportRouteInput input = new ImportRouteInput(msg.householdId(), tripId, null, format, content);
        return routes.importRoute(input)
                .flatMap(route -> boardAndReply(msg, route, trip))
                .onErrorResume(e -> {
                    log.warn("route store rejected the import: {}", e.toString());
                    return Mono.just(reply(rejectMsg));
                });
    }

    private Mono<IntentResponse> boardAndReply(NormalizedMessage msg, RouteDto route, TripDto trip) {
        String mapLink = mapLink(route);
        String text = replyText(route, trip, mapLink);
        return publishBoard(msg, route, trip, mapLink)
                .map(link -> reply(withBoardLink(text, link)))
                .defaultIfEmpty(reply(text))
                .onErrorResume(e -> {
                    log.warn("route board store failed: {}", e.toString());
                    return Mono.just(reply(DegradedNotice.append(text,
                            "не смог собрать HTML-доску маршрута сейчас — показал только текстом, попробуйте позже")));
                });
    }

    // --- format sniffing (content-based, no filename) ---

    /** GPX/GeoJSON/KML/KMZ from the bytes: ZIP magic → kmz; else the XML root / JSON leading token. */
    static String sniffFormat(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        // ZIP local-file-header magic "PK\x03\x04" (also \x05\x06 empty / \x07\x08 spanned) → a KMZ archive.
        if (bytes.length >= 4 && bytes[0] == 0x50 && bytes[1] == 0x4B
                && (bytes[2] == 0x03 || bytes[2] == 0x05 || bytes[2] == 0x07)) {
            return "kmz";
        }
        String head = new String(bytes, 0, Math.min(bytes.length, 2048), StandardCharsets.UTF_8)
                .replace("﻿", "").trim();
        String lower = head.toLowerCase(Locale.ROOT);
        if (lower.startsWith("{") || lower.startsWith("[")) {
            return "geojson";
        }
        if (lower.contains("<gpx")) {
            return "gpx";
        }
        if (lower.contains("<kml")) {
            return "kml";
        }
        if (lower.contains("\"type\"")
                && (lower.contains("featurecollection") || lower.contains("linestring")
                    || lower.contains("\"feature\"") || lower.contains("\"point\""))) {
            return "geojson";
        }
        return null;
    }

    // --- reply + board ---

    private String replyText(RouteDto route, TripDto trip, String mapLink) {
        StringBuilder sb = new StringBuilder("Импортировал маршрут «").append(route.name()).append("»: ")
                .append(route.pointCount()).append(" точек");
        if (route.distanceM() != null) {
            sb.append(", ~").append(km(route.distanceM())).append(" км");
        }
        sb.append('.');
        if (trip != null) {
            sb.append(" Добавил к поездке «").append(trip.title()).append("».");
        }
        if (mapLink != null) {
            sb.append("\nНа карте: ").append(mapLink);
        }
        return sb.toString();
    }

    private Mono<String> publishBoard(NormalizedMessage msg, RouteDto route, TripDto trip, String mapLink) {
        Doc.Builder b = Doc.builder("Маршрут")
                .kicker(trip != null
                        ? "Поездка · " + (blank(trip.destination()) ? trip.title() : trip.destination())
                        : "Импортированный маршрут")
                .subtitle(route.name());

        List<String> rows = new ArrayList<>();
        rows.add("Точек: " + route.pointCount());
        if (route.distanceM() != null) {
            rows.add("Дистанция: ~" + km(route.distanceM()) + " км");
        }
        rows.add("Формат: " + route.sourceFormat().toUpperCase(Locale.ROOT));
        if (mapLink != null) {
            rows.add("На карте: " + mapLink);
        }
        b.section("Маршрут", rows);

        return publisher.publish(msg.householdId(), msg.userId(), b.build());
    }

    /** An OpenStreetMap marker link built from the route's first point — a plain URL, no external call. */
    private static String mapLink(RouteDto route) {
        double[] p = firstPoint(route.geometry());
        if (p == null) {
            return null;
        }
        String lat = String.valueOf(p[0]);
        String lon = String.valueOf(p[1]);
        return "https://www.openstreetmap.org/?mlat=" + lat + "&mlon=" + lon + "#map=12/" + lat + "/" + lon;
    }

    private static double[] firstPoint(JsonNode geometry) {
        if (geometry == null) {
            return null;
        }
        double[] fromTrack = firstOf(geometry.get("track"));
        if (fromTrack != null) {
            return fromTrack;
        }
        return firstOf(geometry.get("waypoints"));
    }

    private static double[] firstOf(JsonNode points) {
        if (points == null || !points.isArray() || points.isEmpty()) {
            return null;
        }
        JsonNode p = points.get(0);
        if (p == null || !p.hasNonNull("lat") || !p.hasNonNull("lon")) {
            return null;
        }
        return new double[]{p.get("lat").asDouble(), p.get("lon").asDouble()};
    }

    private static String km(BigDecimal metres) {
        return metres.divide(METRES_PER_KM, 1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String withBoardLink(String text, String link) {
        return (link == null || link.isBlank()) ? text : text + "\n\nОткрыть на доске: " + link;
    }

    private IntentResponse reply(String text) {
        return new IntentResponse(manifest.name(), text, null);
    }
}
