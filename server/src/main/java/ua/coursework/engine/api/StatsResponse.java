package ua.coursework.engine.api;

import com.fasterxml.jackson.annotation.JsonProperty;

public record StatsResponse(
    @JsonProperty("documents") int documents,
    @JsonProperty("index_build_time_ms") long indexBuildTimeMs,
    @JsonProperty("threads_used") int threadsUsed) {}
