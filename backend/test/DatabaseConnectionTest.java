import database.DatabaseConnection;

import java.sql.Connection;
import java.io.File;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Enumeration;

/**
 * DatabaseConnectionTest  (Phase 3)
 * ---------------------------------
 * A simple self-made test program (no JUnit needed). It prints PASS or FAIL for each check.
 *
 * Run it from the PROJECT ROOT folder (see README / instructions):
 *     java -cp "out;lib\*" DatabaseConnectionTest
 *
 * The program ends with exit code 0 if everything passed, and 1 if anything failed.
 * It never changes real data: the one write test is rolled back.
 */
public class DatabaseConnectionTest {

    private static int passed = 0;
    private static int failed = 0;

    /** Records one result and prints it. */
    private static void check(String testName, boolean condition, String detail) {
        if (condition) {
            passed++;
            System.out.println("  PASS  " + testName + (detail.isEmpty() ? "" : "  -> " + detail));
        } else {
            failed++;
            System.out.println("  FAIL  " + testName + (detail.isEmpty() ? "" : "  -> " + detail));
        }
    }

    /** Runs "SELECT COUNT(*) FROM <table>" and returns the number. */
    private static int countRows(Connection con, String table) throws SQLException {
        // The table name is a fixed word written by us (not user input), so building the SQL this way is safe.
        try (PreparedStatement ps = con.prepareStatement("SELECT COUNT(*) FROM " + table);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    public static void main(String[] args) {
        System.out.println("=== Phase 3: Database connection tests ===");

        // ---------- Test 1: settings file ----------
        System.out.println("\n[1] Configuration");
        try {
            String url = DatabaseConnection.getUrl();
            String user = DatabaseConnection.getUser();
            check("config.properties is found and readable", url != null && user != null,
                    "url=" + url + ", user=" + user);
        } catch (RuntimeException e) {
            check("config.properties is found and readable", false, e.getMessage());
            finish();   // nothing else can work without the settings
            return;
        }

        // ---------- Test 1b: JDBC driver ----------
        System.out.println("\n[1b] JDBC driver (the MySQL Connector/J .jar)");
        StringBuilder loaded = new StringBuilder();
        Enumeration<Driver> drivers = DriverManager.getDrivers();
        while (drivers.hasMoreElements()) {
            loaded.append(drivers.nextElement().getClass().getName()).append(' ');
        }
        boolean driverFound = loaded.length() > 0;
        if (driverFound) {
            check("A JDBC driver is on the classpath", true, loaded.toString().trim());
        } else {
            // Help the student: show what is really inside the lib folder.
            File lib = new File("lib");
            String[] names = lib.list();
            String listing = !lib.isDirectory() ? "folder 'lib' not found in " + new File("").getAbsolutePath()
                    : (names == null || names.length == 0 ? "lib is EMPTY" : String.join(", ", names));
            check("A JDBC driver is on the classpath", false,
                    "none found. Files in lib: [" + listing + "]. A file ending in .jar must sit DIRECTLY in lib "
                    + "(not inside a sub-folder, and not a .zip)");
        }

        // ---------- Test 2: connection ----------
        System.out.println("\n[2] Connection");
        try (Connection con = DatabaseConnection.getConnection()) {
            check("Connection opens", con != null && !con.isClosed(), "");
            check("Connection is valid (server answers)", con.isValid(5), "");
            check("Connected to database campus_route_db",
                    "campus_route_db".equals(con.getCatalog()), "catalog=" + con.getCatalog());

            // ---------- Test 3: tables ----------
            System.out.println("\n[3] Tables exist");
            DatabaseMetaData meta = con.getMetaData();
            String[] tables = { "locations", "pathways", "facilities", "administrators", "campus_conditions" };
            for (String t : tables) {
                try (ResultSet rs = meta.getTables(con.getCatalog(), null, t, new String[] { "TABLE" })) {
                    check("Table '" + t + "' exists", rs.next(), "");
                }
            }

            // ---------- Test 4: sample data ----------
            System.out.println("\n[4] Sample data was loaded by campus_db.sql");
            int locations = countRows(con, "locations");
            int pathways = countRows(con, "pathways");
            int facilities = countRows(con, "facilities");
            int admins = countRows(con, "administrators");
            check("locations has rows (expected 18 with the sample data)", locations >= 15, "found " + locations);
            check("pathways has rows (expected 32 with the sample data)", pathways >= 20, "found " + pathways);
            check("facilities has rows (expected 32 with the sample data)", facilities > 0, "found " + facilities);
            check("administrators has the sample admin", admins >= 1, "found " + admins);

            // ---------- Test 5: PreparedStatement with a parameter ----------
            System.out.println("\n[5] PreparedStatement");
            try (PreparedStatement ps = con.prepareStatement(
                    "SELECT location_name FROM locations WHERE location_id = ?")) {
                ps.setInt(1, 1);                       // the ? becomes 1
                try (ResultSet rs = ps.executeQuery()) {
                    boolean found = rs.next();
                    check("Query with a parameter returns location 1 = Main Gate",
                            found && "Main Gate".equals(rs.getString("location_name")),
                            found ? rs.getString("location_name") : "no row");
                }
            }

            // ---------- Test 6: SQL injection is harmless ----------
            System.out.println("\n[6] SQL injection protection");
            String attack = "x' OR '1'='1";   // a classic attack text
            try (PreparedStatement ps = con.prepareStatement(
                    "SELECT COUNT(*) FROM locations WHERE location_name = ?")) {
                ps.setString(1, attack);        // treated purely as TEXT, never as SQL
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    check("Attack text matches 0 rows (it is treated as plain text)",
                            rs.getInt(1) == 0, "rows matched = " + rs.getInt(1));
                }
            }

            // ---------- Test 7: write + rollback ----------
            System.out.println("\n[7] Insert into campus_conditions, then roll back");
            int before = countRows(con, "campus_conditions");
            con.setAutoCommit(false);           // start a transaction: nothing is permanent until commit()
            try {
                try (PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO campus_conditions (pathway_id, crowd_level, pathway_status) VALUES (?, ?, ?)")) {
                    ps.setInt(1, 1);
                    ps.setString(2, "HIGH");
                    ps.setString(3, "OPEN");
                    check("INSERT affects exactly 1 row", ps.executeUpdate() == 1, "");
                }
                int during = countRows(con, "campus_conditions");
                check("Row count increases inside the transaction", during == before + 1,
                        before + " -> " + during);
            } finally {
                con.rollback();                 // undo the test insert
                con.setAutoCommit(true);
            }
            int after = countRows(con, "campus_conditions");
            check("Rollback restores the original row count", after == before, "count = " + after);

        } catch (SQLException e) {
            check("Database operations", false, e.getMessage());
        }

        // ---------- Test 8: wrong password ----------
        System.out.println("\n[8] Wrong password is rejected");
        try (Connection bad = DatabaseConnection.getConnection(
                DatabaseConnection.getUrl(), DatabaseConnection.getUser(), "definitely-wrong-password")) {
            check("Connection with a wrong password must fail", false, "it connected!");
        } catch (SQLException e) {
            // It must fail BECAUSE of the login, not because the driver is missing.
            check("Connection with a wrong password is refused (message says 'Login refused')",
                    e.getMessage().contains("Login refused"), e.getMessage());
        }

        finish();
    }

    private static void finish() {
        System.out.println("\n=== Result: " + passed + " passed, " + failed + " failed ===");
        System.exit(failed == 0 ? 0 : 1);
    }
}
