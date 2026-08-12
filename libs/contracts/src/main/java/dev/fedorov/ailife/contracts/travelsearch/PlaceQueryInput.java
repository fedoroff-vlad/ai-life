package dev.fedorov.ailife.contracts.travelsearch;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Body for {@code POST /internal/resolve-place}: a human place name to resolve to search codes. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PlaceQueryInput(String query) {
}
