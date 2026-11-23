package ua.coursework.engine.search;

import java.util.Objects;

/**
 * A query term with a weight. Terms typed by the user have weight 1.0; terms added by the LLM later
 * get a smaller weight, so they help to find documents but count less than the original words.
 */
public record WeightedTerm(String term, double weight) {

  public WeightedTerm {
    Objects.requireNonNull(term, "term");
    if (term.isBlank()) {
      throw new IllegalArgumentException("term must not be blank");
    }
    if (!(weight > 0) || Double.isInfinite(weight)) { // "!(weight > 0)" is also true for NaN
      throw new IllegalArgumentException("weight must be a positive finite number, got " + weight);
    }
  }

  /** A term with the default weight 1.0. */
  public static WeightedTerm of(String term) {
    return new WeightedTerm(term, 1.0);
  }
}
