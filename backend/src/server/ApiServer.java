package server;

import com.sun.net.httpserver.HttpServer;
import database.DatabaseConnection;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ApiServer - starts Java's built-in web server and connects each address to its handler.
 *
 *   /api/health, /api/locations, /api/pathways, /api/facilities, /api/summary  -> CampusDataHandler
 *   /api/routes/...                                                            -> RouteHandler
 *   /api/recommendations                                                       -> RecommendationHandler
 *   any other /api/...                                                         -> JSON 404
 *   everything else ( / , /index.html, /css/..., /js/... )                     -> StaticFileHandler (the website)
 *
 * Settings (all optional, in backend/config.properties):
 *   server.host            default 127.0.0.1  (only THIS computer can connect; safest for a demo)
 *   server.port            default 8080
 *   frontend.dir           default frontend   (relative to the folder you start the program from)
 *   cors.allowed.origin    default empty      (only needed if the website is served from ANOTHER address)
 */
public class ApiServer {

    private final String host;
    private final int port;
    private final Path frontendDir;
    private final String corsOrigin;
    private HttpServer server;
    private ExecutorService executor;

    public ApiServer(String host, int port, Path frontendDir, String corsOrigin) {
        this.host = host;
        this.port = port;
        this.frontendDir = frontendDir;
        this.corsOrigin = corsOrigin;
    }

    /** Builds a server from backend/config.properties. */
    public static ApiServer fromConfig() {
        String host = DatabaseConnection.getSetting("server.host", "127.0.0.1").trim();
        String portText = DatabaseConnection.getSetting("server.port", "8080").trim();
        int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("server.port in config.properties must be a number (found '" + portText + "')");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalStateException("server.port must be between 1 and 65535 (found " + port + ")");
        }
        Path frontend = Paths.get(DatabaseConnection.getSetting("frontend.dir", "frontend").trim());
        String cors = DatabaseConnection.getSetting("cors.allowed.origin", "").trim();
        return new ApiServer(host, port, frontend, cors);
    }

    /** Starts listening. Throws java.net.BindException if the port is already in use. */
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName(host), port), 0);

        CampusDataHandler data = new CampusDataHandler(corsOrigin);
        server.createContext("/api/health", data);
        server.createContext("/api/locations", data);
        server.createContext("/api/pathways", data);
        server.createContext("/api/facilities", data);
        server.createContext("/api/summary", data);
        server.createContext("/api/routes", new RouteHandler(corsOrigin));
        server.createContext("/api/recommendations", new RecommendationHandler(corsOrigin));
        server.createContext("/api", new NotFoundHandler(corsOrigin));      // any other /api/... address
        server.createContext("/", new StaticFileHandler(frontendDir));      // the website

        executor = Executors.newFixedThreadPool(8);                         // up to 8 requests at the same time
        server.setExecutor(executor);
        server.start();
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    /** The port the server really listens on (useful when started with port 0 = "any free port"). */
    public int getPort() {
        return server.getAddress().getPort();
    }

    /** The port from the settings (works before start(); getPort() works only after start()). */
    public int getConfiguredPort() {
        return port;
    }

    public String getHost() {
        return host;
    }
}
