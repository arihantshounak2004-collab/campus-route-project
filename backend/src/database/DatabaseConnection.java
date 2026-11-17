package database;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * DatabaseConnection
 * ------------------
 * The ONE place in the project that knows how to open a connection to MySQL.
 * Every DAO class (added in Phase 4) will call DatabaseConnection.getConnection().
 *
 * WHAT IS JDBC?
 *   JDBC (Java Database Connectivity) is the standard Java way to talk to a database.
 *   Steps: 1) get a Connection  2) create a PreparedStatement with your SQL
 *          3) run it  4) read the ResultSet  5) close everything.
 *
 * WHERE DO THE DATABASE SETTINGS COME FROM?
 *   From a file called config.properties (NOT from the Java code), so that:
 *     - your password is never written inside source code, and
 *     - config.properties is listed in .gitignore, so it is never shared by accident.
 *   Copy backend/config.properties.example to backend/config.properties and edit it.
 *
 * HOW IS THE FILE FOUND? (first match wins)
 *   1. The path given with   java -Dcampus.config=C:\path\config.properties ...
 *   2. backend/config.properties   (when you run from the project root folder)
 *   3. config.properties           (when you run from inside the backend folder)
 *
 * PASSWORD OVERRIDE:
 *   If the environment variable CAMPUS_DB_PASSWORD is set, it is used instead of
 *   db.password from the file.
 */
public class DatabaseConnection {

    // The settings read from config.properties. Loaded only once, the first time we need them.
    private static Properties settings = null;

    // Nobody should create objects of this class: all methods are static.
    private DatabaseConnection() { }

    /**
     * Opens a NEW connection to the database using the settings in config.properties.
     *
     * The caller must close the connection. The easiest way is try-with-resources:
     *
     *     try (Connection con = DatabaseConnection.getConnection()) {
     *         ... use con ...
     *     }   // con is closed automatically here
     *
     * @throws SQLException if MySQL is not running, the password is wrong, the
     *                      database does not exist, or the JDBC driver jar is missing.
     */
    public static Connection getConnection() throws SQLException {
        Properties p = loadSettings();
        return open(p.getProperty("db.url"), p.getProperty("db.user"), readPassword(p));
    }

    /**
     * Opens a connection with explicit credentials. Used by the tests to prove that a
     * WRONG password is rejected. Normal application code should use getConnection().
     */
    public static Connection getConnection(String url, String user, String password)
            throws SQLException {
        return open(url, user, password);
    }

    /** Reads any value from config.properties (for example "server.port"). */
    public static String getSetting(String key, String defaultValue) {
        return loadSettings().getProperty(key, defaultValue);
    }

    /** The JDBC URL from the config file. It never contains the password, so it is safe to print. */
    public static String getUrl() {
        return loadSettings().getProperty("db.url");
    }

    /** The database user name from the config file. */
    public static String getUser() {
        return loadSettings().getProperty("db.user");
    }

    // ------------------------------------------------------------------
    // Private helper methods
    // ------------------------------------------------------------------

    /** The actual call to DriverManager, with friendlier error messages. */
    private static Connection open(String url, String user, String password) throws SQLException {
        if (url == null || url.trim().isEmpty()) {
            throw new SQLException("db.url is missing in config.properties.");
        }
        try {
            // DriverManager finds the MySQL driver automatically if the Connector/J jar
            // is on the classpath (the lib folder). No Class.forName(...) is needed.
            return DriverManager.getConnection(url, user, password);
        } catch (SQLException e) {
            // SQL state "28000" = login refused (wrong user or password).
            // SQL state starting with "08" = could not reach the server.
            // NOTE: a missing driver ALSO reports "08001", so we test for it FIRST.
            String sqlState = e.getSQLState() == null ? "" : e.getSQLState();
            String hint;
            if (String.valueOf(e.getMessage()).contains("No suitable driver")) {
                hint = "JDBC driver not found. Put the MySQL Connector/J .jar in the lib folder "
                     + "and include lib\\* in the classpath.";
            } else if (sqlState.equals("28000") || e.getErrorCode() == 1045) {
                hint = "Login refused. Check db.user and db.password in config.properties.";
            } else if (e.getErrorCode() == 1049 || e.getErrorCode() == 1044) {
                // 1049 = unknown database, 1044 = this user has no access to that database
                // (MySQL may report either when the database name is wrong or not created yet).
                hint = "The database does not exist or this user may not use it. Run "
                     + "database/campus_db.sql in MySQL Workbench first and check the name in db.url.";
            } else if (sqlState.startsWith("08")) {
                hint = "Cannot reach MySQL. Is the MySQL service running, and is the port in db.url correct?";
            } else {
                hint = "See the original error below.";
            }
            // We keep the original exception as the "cause" so no information is lost.
            throw new SQLException("Database connection failed. " + hint
                    + " (original message: " + e.getMessage() + ")",
                    e.getSQLState(), e.getErrorCode(), e);
        }
    }

    /** Returns the password: environment variable first, then the config file. */
    private static String readPassword(Properties p) {
        String fromEnvironment = System.getenv("CAMPUS_DB_PASSWORD");
        if (fromEnvironment != null && !fromEnvironment.isEmpty()) {
            return fromEnvironment;
        }
        return p.getProperty("db.password", "");
    }

    /** Loads config.properties once and keeps it in memory. */
    private static synchronized Properties loadSettings() {
        if (settings != null) {
            return settings;
        }

        Path file = findConfigFile();
        Properties loaded = new Properties();
        try (InputStream in = new FileInputStream(file.toFile())) {
            loaded.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + file.toAbsolutePath()
                    + ": " + e.getMessage(), e);
        }

        // Fail early with a clear message if the essential settings are missing.
        for (String key : new String[] { "db.url", "db.user" }) {
            String value = loaded.getProperty(key);
            if (value == null || value.trim().isEmpty()) {
                throw new IllegalStateException("Setting '" + key + "' is missing in "
                        + file.toAbsolutePath());
            }
        }
        settings = loaded;
        return settings;
    }

    /** Looks for config.properties in the places listed in the class comment. */
    private static Path findConfigFile() {
        String custom = System.getProperty("campus.config");
        if (custom != null && !custom.trim().isEmpty()) {
            Path p = Paths.get(custom);
            if (Files.isRegularFile(p)) {
                return p;
            }
            throw new IllegalStateException("The file given with -Dcampus.config was not found: "
                    + p.toAbsolutePath());
        }
        String[] candidates = { "backend/config.properties", "config.properties" };
        for (String c : candidates) {
            Path p = Paths.get(c);
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        throw new IllegalStateException(
                "config.properties not found. Copy backend/config.properties.example to "
              + "backend/config.properties and enter your MySQL user and password. "
              + "(Current folder: " + Paths.get("").toAbsolutePath() + ")");
    }
}
