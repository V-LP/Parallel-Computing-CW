package ua.coursework.engine.api;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SearchRequest(
    @JsonProperty("query") String query, @JsonProperty("mode") String mode) {}
