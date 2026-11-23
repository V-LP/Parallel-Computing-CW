package ua.coursework.engine.api;

/** Response body of GET /api/health. */
public record HealthResponse(String status, boolean ollama) {}
