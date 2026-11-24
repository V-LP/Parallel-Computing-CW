package ua.coursework.engine.api;

import com.fasterxml.jackson.annotation.JsonProperty;

public record IndexResponse(
    @JsonProperty("status") String status,
    @JsonProperty("documents_indexed") int documentsIndexed,
    @JsonProperty("time_ms") long timeMs) {}
