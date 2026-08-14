package dev.fedorov.ailife.mcp.travel.parse;

/**
 * The result of parsing one route file: the {@code name} extracted from the file (nullable — the caller
 * falls back to a supplied name or a default) and its normalized {@link RouteGeometry}.
 */
public record ParsedRoute(String name, RouteGeometry geometry) {
}
