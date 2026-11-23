package ua.coursework.engine.api;

import com.fasterxml.jackson.annotation.JsonProperty;

/** One element of "results" in the search response. */
public record SearchResult(
    @JsonProperty("doc_id") String docId,
    @JsonProperty("score") double score,
    @JsonProperty("snippet") String snippet) {}
