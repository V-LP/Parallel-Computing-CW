package ua.coursework.engine.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record SearchResponse(
    @JsonProperty("results") List<SearchResult> results, @JsonProperty("time_ms") long timeMs) {}
