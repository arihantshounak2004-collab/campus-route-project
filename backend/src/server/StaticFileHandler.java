package server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * StaticFileHandler - serves the website files (HTML, CSS, JavaScript, images) from the "frontend"
 * folder, so the Java server alone is enough to run the whole application.
 *
 * SECURITY. The address comes from the browser, so it is treated as hostile:
 *   - only GET and HEAD are allowed;
 *   - the address is decoded, then any "..", backslash, colon, null character or hidden-file
 *     name (starting with ".") is refused;
 *   - the final real path (after resolving symbolic links) must still be INSIDE the frontend folder;
 *   - only known web file types are served (.html .css .js .json .svg .png .jpg .gif .ico .txt .woff2 .webp),
 *     so even a mistake in the folder setting could never expose config.properties or .java files.
 * Anything refused is answered with the same plain "404 Not Found", so nothing is revealed.
 */
class StaticFileHandler implements HttpHandler {

    private static final Map<String, String> TYPES = new HashMap<>();
    static {
        TYPES.put("html", "text/html; charset=utf-8");
        TYPES.put("css", "text/css; charset=utf-8");
        TYPES.put("js", "application/javascript; charset=utf-8");
        TYPES.put("json", "application/json; charset=utf-8");
        TYPES.put("svg", "image/svg+xml");
        TYPES.put("png", "image/png");
        TYPES.put("jpg", "image/jpeg");
        TYPES.put("jpeg", "image/jpeg");
        TYPES.put("gif", "image/gif");
        TYPES.put("webp", "image/webp");
        TYPES.put("ico", "image/x-icon");
        TYPES.put("txt", "text/plain; charset=utf-8");
        TYPES.put("woff2", "font/woff2");
    }

    private static final long MAX_FILE_BYTES = 10L * 1024 * 1024;

    private final Path root;        // normalised absolute path of the frontend folder
    private final Path realRoot;    // the same with symbolic links resolved (null if the folder does not exist)

    StaticFileHandler(Path frontendDir) {
        this.root = frontendDir.toAbsolutePath().normalize();
        Path real = null;
        try {
            real = this.root.toRealPath();
        } catch (IOException e) {
            System.err.println("[warning] Frontend folder not found: " + this.root + "  (the website will answer 404)");
        }
        this.realRoot = real;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        int status = 500;
        try {
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
            exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
            exchange.getResponseHeaders().set("Content-Security-Policy",
                    "default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; frame-ancestors 'none'");

            String method = exchange.getRequestMethod();
            if (!method.equals("GET") && !method.equals("HEAD")) {
                exchange.getResponseHeaders().set("Allow", "GET, HEAD");
                status = sendText(exchange, 405, "Method not allowed");
                return;
            }

            Path file = resolve(exchange.getRequestURI().getPath());
            if (file == null) {
                status = sendText(exchange, 404, "Not found");
                return;
            }
            String name = file.getFileName().toString();
            int dot = name.lastIndexOf('.');
            String type = dot < 0 ? null : TYPES.get(name.substring(dot + 1).toLowerCase());
            if (type == null || Files.size(file) > MAX_FILE_BYTES) {
                status = sendText(exchange, 404, "Not found");
                return;
            }

            byte[] bytes = Files.readAllBytes(file);
            exchange.getResponseHeaders().set("Content-Type", type);
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");        // always pick up edits while developing
            status = 200;
            if (method.equals("HEAD")) {
                exchange.sendResponseHeaders(200, -1);
            } else {
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
        } catch (Throwable t) {
            System.err.println("[error] Static file problem: " + t);
            try {
                status = sendText(exchange, 500, "Internal server error");
            } catch (Throwable ignored) {
                // the answer may already have started
            }
        } finally {
            System.out.println("[" + exchange.getRequestMethod() + "] " + exchange.getRequestURI().getPath() + " -> " + status + " (static)");
            exchange.close();
        }
    }

    /** Returns the real file for this URL path, or null if it must not be served. */
    private Path resolve(String urlPath) {
        if (realRoot == null || urlPath == null || !urlPath.startsWith("/")) {
            return null;
        }
        if (urlPath.indexOf('\0') >= 0 || urlPath.indexOf('\\') >= 0 || urlPath.indexOf(':') >= 0) {
            return null;
        }
        for (String segment : urlPath.split("/")) {
            if (segment.startsWith(".")) {            // "..", ".", ".git", ".env" ...
                return null;
            }
        }
        try {
            Path target = root.resolve(urlPath.substring(1)).normalize();
            if (!target.startsWith(root)) {
                return null;
            }
            if (Files.isDirectory(target)) {
                target = target.resolve("index.html");
            }
            if (!Files.isRegularFile(target)) {
                return null;
            }
            Path real = target.toRealPath();          // follows symbolic links
            return real.startsWith(realRoot) ? real : null;
        } catch (InvalidPathException | IOException e) {
            return null;
        }
    }

    private int sendText(HttpExchange exchange, int status, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        if (exchange.getRequestMethod().equals("HEAD")) {
            exchange.sendResponseHeaders(status, -1);
        } else {
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
        return status;
    }
}
