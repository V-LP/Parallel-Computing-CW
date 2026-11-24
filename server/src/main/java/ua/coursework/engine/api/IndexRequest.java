package ua.coursework.engine.api;

import com.fasterxml.jackson.annotation.JsonProperty;

public record IndexRequest(@JsonProperty("threads") Integer threads) {}
