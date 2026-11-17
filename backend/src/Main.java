import database.DatabaseConnection;
import server.ApiServer;

import java.net.BindException;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Main - the program's starting point.
 * Start it from the PROJECT ROOT folder:   java -cp "out;lib\*" Main
 * Then open   http://localhost:8080   in a browser. Press Ctrl+C in the window to stop.
 */
public class Main {

    public static void main(String[] args) {
        ApiServer server;
        try {
            server = ApiServer.fromConfig();
        } catch (RuntimeException e) {
            System.err.println("Cannot start: " + e.getMessage());
            System.exit(1);
            return;
        }

        // Check the database once at start-up. A problem is only a WARNING: the server still starts,
        // and every request that needs the database will answer 503 until MySQL is available.
        try (Connection con = DatabaseConnection.getConnection()) {
            System.out.println("Database: connected (" + DatabaseConnection.getUrl().replaceAll("\\?.*$", "") + ")");
        } catch (SQLException | RuntimeException e) {
            System.err.println("WARNING: cannot reach the database: " + e.getMessage());
            System.err.println("         The server will start anyway. Fix the problem above, no restart is needed.");
        }

        try {
            server.start();
        } catch (BindException e) {
            System.err.println("Cannot start: port " + server.getConfiguredPort() + " is already in use. Close the other program "
                    + "(maybe an older copy of this server) or change server.port in backend\\config.properties.");
            System.exit(1);
            return;
        } catch (java.io.IOException e) {
            System.err.println("Cannot start the server: " + e.getMessage());
            System.exit(1);
            return;
        }

        final ApiServer running = server;
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                running.stop();
                System.out.println("Server stopped.");
            }
        }));

        System.out.println();
        System.out.println("Campus Route Optimizer is running.");
        System.out.println("Open this address in your browser:  http://localhost:" + server.getPort());
        System.out.println("Press Ctrl+C to stop.");
        System.out.println();
    }
}
