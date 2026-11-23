package ua.coursework.engine.api;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Request body of POST /api/search. */
public record SearchRequest(
    @JsonProperty("query") String query, @JsonProperty("mode") String mode) {}
