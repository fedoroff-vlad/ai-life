package dev.fedorov.ailife.mcp.travel.parse;

/**
 * SPI for one route file format (plans/travel.md §Route import). A new format = one new {@code @Component}
 * implementing this — the {@link RouteImporter} dispatches by {@link #format()} and nothing else changes
 * (the store and the agent are untouched). RT-a ships {@code geojson} + {@code gpx}; RT-b adds {@code kml}
 * / {@code kmz}.
 */
public interface RouteParser {

    /** The lower-case format token this parser handles (e.g. {@code "gpx"}). */
    String format();

    /**
     * Parse owner-supplied file content into a normalized route. Implementations must be side-effect-free
     * and must not resolve external resources (XXE-hardened for XML formats).
     *
     * @throws IllegalArgumentException when the content is not valid for this format
     */
    ParsedRoute parse(String content);
}
