package util;

/** Thrown when data sent by a client is invalid. The server turns it into an HTTP 400 answer. */
public class ValidationException extends RuntimeException {
    public ValidationException(String message) {
        super(message);
    }
}
