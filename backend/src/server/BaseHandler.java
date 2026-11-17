package server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import service.NotFoundException;
import util.JsonUtil;
import util.ValidationException;
import util.ValidationUtil;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * BaseHandler - the common part of every API handler.
 *
 * Every API handler extends this class and only implements route(). This class then guarantees:
 *   - every answer is JSON (also every ERROR answer) with the right HTTP status code;
 *   - a mistake by the client gives 4xx, a problem on the server gives 5xx;
 *   - technical details (stack traces, database URL, SQL messages) are written to the server console
 *     only and are NEVER sent to the browser;
 *   - safe headers are added to every answer;
 *   - one log line per request (method, path, status, time) and never the request body.
 *
 * STATUS CODES USED:
 *   200 OK   400 invalid input   404 not found   405 wrong HTTP method   413 body too large
 *   415 wrong content type   500 bug on the server   503 database not available
 */
abstract class BaseHandler implements HttpHandler {

    protected static final int MAX_BODY_BYTES = 16 * 1024;

    /**
     * The status code of the answer that THIS request has already started to send (null = none yet).
     * It must be per request. (HttpExchange.setAttribute is NOT per request: Java shares those
     * attributes between all requests of the same address, which caused a real bug.)
     * A request is handled start-to-finish by one thread, so a ThreadLocal is correct here.
     */
    private static final ThreadLocal<Integer> SENT_STATUS = new ThreadLocal<>();

    private final String corsOrigin;      // null = same-origin only (no CORS headers)

    BaseHandler(String corsOrigin) {
        this.corsOrigin = (corsOrigin == null || corsOrigin.trim().isEmpty()) ? null : corsOrigin.trim();
    }

    /** Implemented by each handler: look at the request and call sendJson(...), or throw an exception. */
    protected abstract void route(HttpExchange exchange) throws Exception;

    @Override
    public final void handle(HttpExchange exchange) throws IOException {
        long start = System.nanoTime();
        SENT_STATUS.remove();                          // threads are reused: forget the previous request
        try {
            addCommonHeaders(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                if (corsOrigin == null) {
                    throw new ApiException(405, "Method not allowed");
                }
                SENT_STATUS.set(204);
                exchange.sendResponseHeaders(204, -1);            // CORS preflight answer
                return;
            }
            route(exchange);
        } catch (ApiException e) {
            sendError(exchange, e.getStatus(), e.getMessage(), e.getHeaders());
        } catch (ValidationException e) {
            sendError(exchange, 400, e.getMessage(), null);
        } catch (NotFoundException e) {
            sendError(exchange, 404, e.getMessage(), null);
        } catch (SQLException e) {
            System.err.println("[error] Database problem: " + e.getMessage());          // details: console only
            sendError(exchange, 503, "The database is not available right now. Please try again later.", null);
        } catch (Throwable t) {
            System.err.println("[error] Unexpected problem while handling " + exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            t.printStackTrace();                                                      // details: console only
            sendError(exchange, 500, "Internal server error", null);
        } finally {
            Integer status = SENT_STATUS.get();
            SENT_STATUS.remove();
            long ms = (System.nanoTime() - start) / 1_000_000;
            System.out.println("[" + exchange.getRequestMethod() + "] " + exchange.getRequestURI().getPath()
                    + " -> " + (status == null ? "?" : status) + " (" + ms + " ms)");
            exchange.close();
        }
    }

    // ------------------------------------------------------------------
    // Sending answers
    // ------------------------------------------------------------------

    private void addCommonHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        if (corsOrigin != null) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", corsOrigin);
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, OPTIONS");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
            exchange.getResponseHeaders().set("Vary", "Origin");
        }
    }

    protected void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = JsonUtil.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        SENT_STATUS.set(status);
        if ("HEAD".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private void sendError(HttpExchange exchange, int status, String message, Map<String, String> extraHeaders) throws IOException {
        if (SENT_STATUS.get() != null) {
            return;                                       // an answer was already started; nothing more can be sent
        }
        if (extraHeaders != null) {
            for (Map.Entry<String, String> h : extraHeaders.entrySet()) {
                exchange.getResponseHeaders().set(h.getKey(), h.getValue());
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        body.put("status", status);
        sendJson(exchange, status, body);
    }

    // ------------------------------------------------------------------
    // Reading requests
    // ------------------------------------------------------------------

    /** Throws 405 (with an Allow header) unless the request uses one of the allowed methods. */
    protected void requireMethod(HttpExchange exchange, String... allowed) {
        String method = exchange.getRequestMethod();
        for (String a : allowed) {
            if (a.equals(method)) return;
        }
        throw new ApiException(405, "Method not allowed. Use: " + String.join(", ", allowed))
                .withHeader("Allow", String.join(", ", allowed));
    }

    /** Reads the request body as a JSON object, with size, content-type, encoding and syntax checks. */
    protected Map<String, Object> readJsonBody(HttpExchange exchange) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.trim().toLowerCase().startsWith("application/json")) {
            throw new ApiException(415, "Content-Type must be application/json");
        }
        String declared = exchange.getRequestHeaders().getFirst("Content-Length");
        if (declared != null) {
            try {
                if (Long.parseLong(declared.trim()) > MAX_BODY_BYTES) {
                    throw new ApiException(413, "The request body is too large (maximum " + MAX_BODY_BYTES + " bytes)");
                }
            } catch (NumberFormatException e) {
                throw new ValidationException("Invalid Content-Length header");
            }
        }

        byte[] bytes = readLimited(exchange.getRequestBody(), MAX_BODY_BYTES);

        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new ValidationException("The request body must be valid UTF-8 text");
        }
        if (text.trim().isEmpty()) {
            throw new ValidationException("The request body is empty");
        }
        try {
            return ValidationUtil.requireObject(JsonUtil.parse(text));
        } catch (JsonUtil.JsonParseException e) {
            throw new ValidationException(e.getMessage());
        }
    }

    /** Reads at most "limit" bytes. If there is more, answers 413 instead of reading it all into memory. */
    private static byte[] readLimited(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[2048];
        int total = 0;
        int n;
        while ((n = in.read(chunk)) != -1) {
            total += n;
            if (total > limit) {
                throw new ApiException(413, "The request body is too large (maximum " + limit + " bytes)");
            }
            buffer.write(chunk, 0, n);
        }
        return buffer.toByteArray();
    }

    /** Parses ?a=1&b=2 into a map (decoded). Bad percent-escapes give a 400. */
    protected Map<String, String> queryParams(HttpExchange exchange) {
        Map<String, String> params = new LinkedHashMap<>();
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null || raw.isEmpty()) {
            return params;
        }
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            try {
                params.put(URLDecoder.decode(key, "UTF-8"), URLDecoder.decode(value, "UTF-8"));
            } catch (IllegalArgumentException | UnsupportedEncodingException e) {
                throw new ValidationException("The query string contains an invalid escape sequence");
            }
        }
        return params;
    }
}
