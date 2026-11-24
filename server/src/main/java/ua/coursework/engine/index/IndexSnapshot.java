package ua.coursework.engine.index;

public record IndexSnapshot(
    ConcurrentInvertedIndex index, int documents, long buildTimeMs, int threadsUsed) {}
