package ua.coursework.engine.api;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Request body of POST /api/index. {@code threads} is optional (default: number of CPUs). */
public record IndexRequest(@JsonProperty("threads") Integer threads) {}
