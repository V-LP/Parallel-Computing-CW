package ua.coursework.engine.search;

/** One search result: document id (path), score in (0, 1] and a text snippet. */
public record SearchHit(String docId, double score, String snippet) {}
