package ua.coursework.engine.index;

/**
 * One entry of a posting list: the term occurs {@code termFrequency} times in document {@code
 * docId}. The record is immutable, so a posting can be shared between threads without locking.
 */
public record Posting(int docId, int termFrequency) {}
