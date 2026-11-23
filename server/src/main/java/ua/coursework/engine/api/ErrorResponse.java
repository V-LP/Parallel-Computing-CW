package ua.coursework.engine.api;

/** Body of error responses (400, 409, 500). */
public record ErrorResponse(String status, String message) {

  public static ErrorResponse of(String message) {
    return new ErrorResponse("error", message);
  }
}
