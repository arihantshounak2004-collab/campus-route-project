package server;

import java.util.LinkedHashMap;
import java.util.Map;

/** An error that should be sent to the client with a specific HTTP status code (404, 405, 413 ...). */
public class ApiException extends RuntimeException {
    private final int status;
    private final Map<String, String> headers = new LinkedHashMap<>();

    public ApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public ApiException withHeader(String name, String value) {
        headers.put(name, value);
        return this;
    }

    public int getStatus()                  { return status; }
    public Map<String, String> getHeaders() { return headers; }
}
