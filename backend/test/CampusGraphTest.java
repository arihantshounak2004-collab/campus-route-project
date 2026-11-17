import dao.LocationDAO;
import dao.PathwayDAO;
import graph.CampusGraph;
import graph.Edge;
import model.CrowdLevel;
import model.Location;
import model.LocationType;
import model.Pathway;
import model.PathwayStatus;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.TreeSet;

/**
 * CampusGraphTest  (Phase 4)
 * --------------------------
 * Part A  - builds small graphs by hand (NO database needed)
 * Part B  - tests LocationDAO and PathwayDAO against MySQL
 * Part C  - tests the graph built from the real MySQL data
 *
 * Run from the PROJECT ROOT:   java -cp "out;lib\*" CampusGraphTest
 * Exit code 0 = all passed, 1 = something failed.
 */
public class CampusGraphTest {

    private static int passed = 0;
    private static int failed = 0;

    private static void check(String name, boolean ok, String detail) {
        if (ok) { passed++; } else { failed++; }
        System.out.println("  " + (ok ? "PASS" : "FAIL") + "  " + name + (detail.isEmpty() ? "" : "  -> " + detail));
    }

    // Small helpers to create test data quickly
    private static Location loc(int id, String name, boolean active) {
        return new Location(id, name, LocationType.BUILDING, "test", id * 10, id * 10, active);
    }
    private static Pathway path(int id, int a, int b, double dist, PathwayStatus status) {
        return new Pathway(id, a, b, dist, (int) Math.round(dist / 1.2), CrowdLevel.LOW, status, true);
    }

    public static void main(String[] args) {
        System.out.println("=== Phase 4: Graph tests ===");
        partA_inMemory();
        partB_daos();
        partC_graphFromDatabase();
        System.out.println("\n=== Result: " + passed + " passed, " + failed + " failed ===");
        System.exit(failed == 0 ? 0 : 1);
    }

    // =================================================================
    // PART A: hand-made graphs, no database
    // =================================================================
    private static void partA_inMemory() {
        System.out.println("\n[A] In-memory graph (no database)");

        // Locations: 1, 2, 3 active.  4 is INACTIVE.
        List<Location> locations = Arrays.asList(
                loc(1, "A", true), loc(2, "B", true), loc(3, "C", true), loc(4, "D (inactive)", false));

        // Pathways. Some are deliberately INVALID to test the safety rules.
        List<Pathway> pathways = Arrays.asList(
                path(1, 1, 2, 100, PathwayStatus.OPEN),      // valid
                path(2, 2, 3, 50, PathwayStatus.BLOCKED),    // valid (blocked paths are still stored)
                path(3, 3, 4, 40, PathwayStatus.OPEN),       // INVALID: location 4 is inactive
                path(4, 1, 9, 40, PathwayStatus.OPEN),       // INVALID: location 9 does not exist
                path(5, 2, 1, 100, PathwayStatus.OPEN),      // INVALID: duplicate of 1-2 (reverse order)
                path(6, 3, 3, 10, PathwayStatus.OPEN),       // INVALID: self-loop
                path(7, 1, 3, 0, PathwayStatus.OPEN));       // INVALID: distance 0

        CampusGraph g = new CampusGraph(locations, pathways);

        check("Inactive location is left out (3 of 4 locations kept)", g.getLocationCount() == 3, "count=" + g.getLocationCount());
        check("Only the 2 valid pathways are kept", g.getPathwayCount() == 2, "count=" + g.getPathwayCount());
        check("Adjacency list holds each pathway in both directions (4 directed edges)",
                g.getDirectedEdgeCount() == 4, "directed=" + g.getDirectedEdgeCount());
        check("5 invalid rows produced 5 warnings (+1 for the inactive location = 6)",
                g.getWarnings().size() == 6, "warnings=" + g.getWarnings().size());

        check("contains(4) is false for the inactive location", !g.contains(4), "");
        check("getLocation(1) returns A", g.getLocation(1) != null && "A".equals(g.getLocation(1).getName()), "");
        check("getLocation(99) returns null", g.getLocation(99) == null, "");
        check("findLocationByName is not case-sensitive", g.findLocationByName("  b ") != null
                && g.findLocationByName("  b ").getId() == 2, "");

        // Neighbours of B (2): A (via pathway 1) and C (via pathway 2)
        Set<Integer> neighborsOfB = new TreeSet<>();
        for (Edge e : g.getNeighbors(2)) {
            neighborsOfB.add(e.getNeighborId());
        }
        check("Neighbours of B are {1, 3}", neighborsOfB.equals(new TreeSet<>(Arrays.asList(1, 3))), neighborsOfB.toString());

        // The BLOCKED pathway must still be present, with its status intact.
        boolean blockedKept = false;
        for (Edge e : g.getNeighbors(3)) {
            if (e.getNeighborId() == 2 && e.getPathway().getStatus() == PathwayStatus.BLOCKED) {
                blockedKept = true;
            }
        }
        check("BLOCKED pathway is kept in the graph with status BLOCKED (algorithms decide to skip it)", blockedKept, "");

        check("Neighbours of an unknown id = empty list (no crash)", g.getNeighbors(99).isEmpty(), "");

        boolean readOnly = false;
        try {
            g.getNeighbors(1).clear();
        } catch (UnsupportedOperationException e) {
            readOnly = true;
        }
        check("Neighbour list is read-only (outside code cannot damage the graph)", readOnly, "");

        // Empty graph
        CampusGraph empty = new CampusGraph(new ArrayList<Location>(), new ArrayList<Pathway>());
        check("Empty input gives an empty graph", empty.getLocationCount() == 0 && empty.getPathwayCount() == 0, "");

        // Enum parsing
        boolean parseOk = CrowdLevel.parse(" high ") == CrowdLevel.HIGH && PathwayStatus.parse("blocked") == PathwayStatus.BLOCKED;
        boolean parseBad = false;
        try {
            CrowdLevel.parse("EXTREME");
        } catch (IllegalArgumentException e) {
            parseBad = true;
        }
        check("Enum parse accepts ' high ' and 'blocked', rejects 'EXTREME'", parseOk && parseBad, "");
    }

    // =================================================================
    // PART B: DAOs against MySQL
    // =================================================================
    private static void partB_daos() {
        System.out.println("\n[B] LocationDAO and PathwayDAO (MySQL)");
        try {
            LocationDAO locationDAO = new LocationDAO();
            PathwayDAO pathwayDAO = new PathwayDAO();

            List<Location> locations = locationDAO.findAll();
            check("LocationDAO.findAll() returns the sample locations (18)", locations.size() == 18, "found " + locations.size());
            check("First location is Main Gate of type MAIN_GATE",
                    !locations.isEmpty() && "Main Gate".equals(locations.get(0).getName())
                            && locations.get(0).getType() == LocationType.MAIN_GATE,
                    locations.isEmpty() ? "empty" : locations.get(0).toString());

            Location library = locationDAO.findById(8);
            check("LocationDAO.findById(8) = Central Library (LIBRARY, x=450, y=260)",
                    library != null && "Central Library".equals(library.getName())
                            && library.getType() == LocationType.LIBRARY && library.getX() == 450 && library.getY() == 260,
                    String.valueOf(library));
            check("LocationDAO.findById(9999) returns null", locationDAO.findById(9999) == null, "");

            List<Pathway> pathways = pathwayDAO.findAll();
            check("PathwayDAO.findAll() returns the sample pathways (32)", pathways.size() == 32, "found " + pathways.size());

            Pathway first = pathwayDAO.findById(1);
            check("Pathway 1 = Main Gate <-> Administration Block, 88.0 m, HIGH crowd, OPEN",
                    first != null && first.getSourceId() == 1 && first.getDestinationId() == 2
                            && Math.abs(first.getDistance() - 88.0) < 0.001
                            && first.getCrowdLevel() == CrowdLevel.HIGH && first.getStatus() == PathwayStatus.OPEN,
                    String.valueOf(first));
            check("PathwayDAO.findById(9999) returns null", pathwayDAO.findById(9999) == null, "");

            int blocked = 0, restricted = 0, notAccessible = 0;
            for (Pathway p : pathways) {
                if (p.getStatus() == PathwayStatus.BLOCKED) blocked++;
                if (p.getStatus() == PathwayStatus.RESTRICTED) restricted++;
                if (!p.isAccessible()) notAccessible++;
            }
            check("Sample data has 1 BLOCKED, 2 RESTRICTED and 3 non-accessible pathways",
                    blocked == 1 && restricted == 2 && notAccessible == 3,
                    blocked + " / " + restricted + " / " + notAccessible);
        } catch (SQLException | RuntimeException e) {
            check("DAO operations", false, e.getMessage());
        }
    }

    // =================================================================
    // PART C: the graph built from MySQL
    // =================================================================
    private static void partC_graphFromDatabase() {
        System.out.println("\n[C] CampusGraph loaded from MySQL");
        try {
            CampusGraph g = CampusGraph.loadFromDatabase();

            check("Graph has 18 locations", g.getLocationCount() == 18, "found " + g.getLocationCount());
            check("Graph has 32 pathways", g.getPathwayCount() == 32, "found " + g.getPathwayCount());
            check("Directed edges = 2 x pathways = 64", g.getDirectedEdgeCount() == 64, "found " + g.getDirectedEdgeCount());
            check("No warnings (every database row was valid)", g.getWarnings().isEmpty(), g.getWarnings().toString());

            // Neighbours of Main Gate (id 1): Admin Block (2), Visitor Parking (3), Staff Parking (4)
            Set<Integer> gateNeighbors = new TreeSet<>();
            for (Edge e : g.getNeighbors(1)) gateNeighbors.add(e.getNeighborId());
            check("Main Gate neighbours are {2, 3, 4}", gateNeighbors.equals(new TreeSet<>(Arrays.asList(2, 3, 4))), gateNeighbors.toString());

            // Neighbours of Central Library (id 8): 2, 6, 9, 10, 17
            Set<Integer> libNeighbors = new TreeSet<>();
            for (Edge e : g.getNeighbors(8)) libNeighbors.add(e.getNeighborId());
            check("Central Library neighbours are {2, 6, 9, 10, 17}",
                    libNeighbors.equals(new TreeSet<>(Arrays.asList(2, 6, 9, 10, 17))), libNeighbors.toString());

            // Symmetry: if A lists B then B must list A, through the SAME Pathway object.
            boolean symmetric = true;
            for (Location a : g.getLocations()) {
                for (Edge e : g.getNeighbors(a.getId())) {
                    boolean back = false;
                    for (Edge r : g.getNeighbors(e.getNeighborId())) {
                        if (r.getNeighborId() == a.getId() && r.getPathway() == e.getPathway()) back = true;
                    }
                    if (!back) symmetric = false;
                }
            }
            check("Graph is symmetric (every edge exists in both directions)", symmetric, "");

            // The blocked pathway (Computer Science Block 6 <-> Library 8) is stored with status BLOCKED.
            boolean blockedFound = false;
            for (Edge e : g.getNeighbors(6)) {
                if (e.getNeighborId() == 8 && e.getPathway().getStatus() == PathwayStatus.BLOCKED) blockedFound = true;
            }
            check("Blocked path CS Block <-> Library is present with status BLOCKED", blockedFound, "");

            // Connectivity: breadth-first search from Main Gate should reach all 18 locations.
            Set<Integer> seen = new HashSet<>();
            Queue<Integer> queue = new ArrayDeque<>();
            seen.add(1);
            queue.add(1);
            while (!queue.isEmpty()) {
                int current = queue.poll();
                for (Edge e : g.getNeighbors(current)) {
                    if (seen.add(e.getNeighborId())) queue.add(e.getNeighborId());
                }
            }
            check("Every location is reachable from Main Gate (graph is connected)", seen.size() == 18, "reached " + seen.size());

            check("findLocationByName(\"central library\") finds id 8",
                    g.findLocationByName("central library") != null && g.findLocationByName("central library").getId() == 8, "");

            System.out.println("\n  Adjacency list built from MySQL (location id, name -> neighbour ids):");
            for (String line : g.describe().split("\n")) {
                System.out.println("    " + line);
            }
        } catch (SQLException | RuntimeException e) {
            check("Loading the graph from MySQL", false, e.getMessage());
        }
    }
}
