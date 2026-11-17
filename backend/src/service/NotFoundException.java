package service;

/** Thrown when something the client asked for does not exist (for example an unknown location id). The server answers HTTP 404. */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
