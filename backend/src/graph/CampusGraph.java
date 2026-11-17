package graph;

import dao.LocationDAO;
import dao.PathwayDAO;
import model.Location;
import model.Pathway;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CampusGraph
 * -----------
 * The campus as a WEIGHTED, UNDIRECTED GRAPH.
 *
 *   vertex = a Location
 *   edge   = a Pathway (walkable in both directions)
 *   weight = distance / time (chosen by the algorithm); crowd, status and
 *            accessibility are stored on the Pathway for the context-aware router.
 *
 * ADJACENCY LIST: for every location id we keep a list of its Edges.
 *
 *     Map<Integer, List<Edge>>
 *        1 -> [ ->2, ->3, ->4 ]
 *        2 -> [ ->1, ->5, ->8, ... ]
 *
 * It is the standard way to store a graph that has few edges per vertex (a campus
 * has about 3-5 paths per building), and it lets an algorithm ask
 * "which places can I walk to from here?" very quickly: getNeighbors(id).
 *
 * IMPORTANT DESIGN CHOICE: this class stores EVERY valid pathway, including BLOCKED
 * and RESTRICTED ones. Deciding what to ignore is the job of each routing algorithm
 * (the plain shortest-path algorithms ignore status, the context-aware one does not).
 * That way the map can still draw blocked paths in red.
 *
 * The graph is built from data that comes from MySQL (see loadFromDatabase()).
 * It is a SNAPSHOT: if an admin changes the database, build a new graph.
 */
public class CampusGraph {

    // LinkedHashMap keeps insertion order (so printing is tidy and predictable).
    private final Map<Integer, Location> locations = new LinkedHashMap<>();
    private final Map<Integer, List<Edge>> adjacency = new LinkedHashMap<>();
    private final List<Pathway> pathways = new ArrayList<>();      // each pathway once
    private final List<String> warnings = new ArrayList<>();       // why some rows were skipped

    /**
     * Builds the graph from lists. This constructor does NOT touch the database,
     * so it can be tested with small hand-made data.
     *
     * Rules (a bad row is skipped and a warning is recorded, it never crashes the program):
     *   - inactive locations are left out of the graph
     *   - a pathway must join two locations that are in the graph
     *   - a pathway cannot join a location to itself
     *   - distance and time must be greater than zero
     *   - the same pair of locations may be joined only once (A-B and B-A count as the same)
     */
    public CampusGraph(List<Location> allLocations, List<Pathway> allPathways) {
        // Step 1: add the vertices
        for (Location loc : allLocations) {
            if (!loc.isActive()) {
                warnings.add("Location " + loc.getId() + " (" + loc.getName() + ") is inactive and was left out.");
                continue;
            }
            if (locations.containsKey(loc.getId())) {
                warnings.add("Duplicate location id " + loc.getId() + " ignored.");
                continue;
            }
            locations.put(loc.getId(), loc);
            adjacency.put(loc.getId(), new ArrayList<>());
        }

        // Step 2: add the edges
        Set<String> usedPairs = new HashSet<>();   // remembers "smallerId-largerId" of added pathways
        for (Pathway p : allPathways) {
            int a = p.getSourceId();
            int b = p.getDestinationId();
            String problem = null;

            if (a == b) {
                problem = "joins a location to itself";
            } else if (!locations.containsKey(a) || !locations.containsKey(b)) {
                problem = "refers to a missing or inactive location";
            } else if (p.getDistance() <= 0 || p.getEstimatedTime() <= 0) {
                problem = "has a distance or time that is not positive";
            } else if (!usedPairs.add(Math.min(a, b) + "-" + Math.max(a, b))) {
                problem = "duplicates an existing pathway between the same two locations";
            }

            if (problem != null) {
                warnings.add("Pathway " + p.getId() + " skipped: it " + problem + ".");
                continue;
            }

            // Add the edge in BOTH directions (undirected graph).
            adjacency.get(a).add(new Edge(b, p));
            adjacency.get(b).add(new Edge(a, p));
            pathways.add(p);
        }
    }

    /**
     * Loads all locations and pathways from MySQL and builds the graph.
     * This is the method the rest of the application uses.
     */
    public static CampusGraph loadFromDatabase() throws SQLException {
        List<Location> locationList = new LocationDAO().findAll();
        List<Pathway> pathwayList = new PathwayDAO().findAll();
        return new CampusGraph(locationList, pathwayList);
    }

    // ------------------------------------------------------------------
    // Questions an algorithm or handler can ask the graph
    // ------------------------------------------------------------------

    /** True if this location id is a vertex of the graph. */
    public boolean contains(int locationId) {
        return locations.containsKey(locationId);
    }

    /** The Location with this id, or null if it is not in the graph. */
    public Location getLocation(int locationId) {
        return locations.get(locationId);
    }

    /** Finds a location by its name (ignoring upper/lower case). Returns null if not found. */
    public Location findLocationByName(String name) {
        if (name == null) {
            return null;
        }
        for (Location loc : locations.values()) {
            if (loc.getName().equalsIgnoreCase(name.trim())) {
                return loc;
            }
        }
        return null;
    }

    /** All locations in the graph (read-only). */
    public Collection<Location> getLocations() {
        return Collections.unmodifiableCollection(locations.values());
    }

    /**
     * The edges leaving a location (read-only). This is the heart of every graph algorithm.
     * Returns an empty list if the id is not in the graph.
     */
    public List<Edge> getNeighbors(int locationId) {
        List<Edge> edges = adjacency.get(locationId);
        return edges == null ? Collections.<Edge>emptyList() : Collections.unmodifiableList(edges);
    }

    /** Every pathway exactly once (read-only), including blocked and restricted ones. */
    public List<Pathway> getPathways() {
        return Collections.unmodifiableList(pathways);
    }

    /** Number of vertices (locations). */
    public int getLocationCount() { return locations.size(); }

    /** Number of pathways (each undirected pathway counted once). */
    public int getPathwayCount() { return pathways.size(); }

    /** Number of directed edges in the adjacency list. Always 2 x getPathwayCount(). */
    public int getDirectedEdgeCount() {
        int total = 0;
        for (List<Edge> list : adjacency.values()) {
            total += list.size();
        }
        return total;
    }

    /** Messages about rows that were skipped while building (empty if everything was valid). */
    public List<String> getWarnings() {
        return Collections.unmodifiableList(warnings);
    }

    /** A readable picture of the adjacency list, for debugging and for the tests. */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, List<Edge>> entry : adjacency.entrySet()) {
            sb.append(String.format("%2d %-24s -> ", entry.getKey(), locations.get(entry.getKey()).getName()));
            for (int i = 0; i < entry.getValue().size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(entry.getValue().get(i).getNeighborId());
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
