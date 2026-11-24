package ua.coursework.engine.search;

public record SearchHit(String docId, double score, String snippet) {}
