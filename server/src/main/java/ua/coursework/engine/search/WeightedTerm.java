package ua.coursework.engine.search;

import java.util.Objects;

public record WeightedTerm(String term, double weight) {

  public WeightedTerm {
    Objects.requireNonNull(term, "term");
    if (term.isBlank()) {
      throw new IllegalArgumentException("term must not be blank");
    }
    if (!(weight > 0) || Double.isInfinite(weight)) { // catches NaN too
      throw new IllegalArgumentException("weight must be a positive finite number, got " + weight);
    }
  }

  public static WeightedTerm of(String term) {
    return new WeightedTerm(term, 1.0);
  }
}
