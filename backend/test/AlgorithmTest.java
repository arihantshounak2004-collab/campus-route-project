import algorithm.AStarAlgorithm;
import algorithm.DijkstraAlgorithm;
import graph.CampusGraph;
import model.CostMetric;
import model.CrowdLevel;
import model.Location;
import model.LocationType;
import model.Pathway;
import model.PathwayStatus;
import model.RouteResult;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * AlgorithmTest  (Phase 5)
 * ------------------------
 * Tests Dijkstra and A*.
 *
 * Part A - tiny graphs whose answers were calculated BY HAND (no database)
 * Part B - special cases: same place, unknown place, unreachable place
 * Part C - the real campus graph from MySQL:
 *            * two hand-checked routes
 *            * EVERY start/destination pair compared with an independent
 *              brute-force method (Floyd-Warshall), for both distance and time
 * Part D - proof that the A* heuristic never overestimates (on the real campus)
 * Part E - 60 random graphs: Dijkstra = A* = Floyd-Warshall
 *
 * Run from the PROJECT ROOT:   java -cp "out;lib\*" AlgorithmTest
 */
public class AlgorithmTest {

    private static int passed = 0;
    private static int failed = 0;
    private static final double EPS = 1e-6;
    private static final DijkstraAlgorithm DIJKSTRA = new DijkstraAlgorithm();
    private static final AStarAlgorithm ASTAR = new AStarAlgorithm();

    private static void check(String name, boolean ok, String detail) {
        if (ok) { passed++; } else { failed++; }
        System.out.println("  " + (ok ? "PASS" : "FAIL") + "  " + name + (detail.isEmpty() ? "" : "  -> " + detail));
    }

    // ---------- helpers to build small test graphs ----------
    private static Location loc(int id, int x, int y) {
        return new Location(id, "N" + id, LocationType.BUILDING, "test", x, y, true);
    }
    private static Pathway edge(int id, int a, int b, double dist, int seconds, PathwayStatus status) {
        return new Pathway(id, a, b, dist, seconds, CrowdLevel.LOW, status, true);
    }

    public static void main(String[] args) {
        System.out.println("=== Phase 5: Dijkstra and A* tests ===");
        partA_handCalculated();
        partB_specialCases();
        partC_realCampus();
        partE_randomGraphs();
        System.out.println("\n=== Result: " + passed + " passed, " + failed + " failed ===");
        System.exit(failed == 0 ? 0 : 1);
    }

    // =================================================================
    // PART A: hand-calculated answers
    // =================================================================
    private static void partA_handCalculated() {
        System.out.println("\n[A] Small graphs with hand-calculated answers");

        // Graph 1 (a line of points; distances in metres, times in seconds = distance x 10)
        //
        //        A(1) --4-- B(2) --3-- D(4)
        //         \        /          /
        //          2      1          6
        //           \    /          /
        //            C(3) ---------
        //
        // A->D by hand:  A-B-D = 4+3 = 7     A-C-D = 2+6 = 8     A-C-B-D = 2+1+3 = 6  <- the best
        List<Location> nodes = Arrays.asList(loc(1, 0, 0), loc(2, 3, 0), loc(3, 2, 0), loc(4, 6, 0));
        List<Pathway> edges = Arrays.asList(
                edge(1, 1, 2, 4, 40, PathwayStatus.OPEN),
                edge(2, 2, 4, 3, 30, PathwayStatus.OPEN),
                edge(3, 1, 3, 2, 20, PathwayStatus.OPEN),
                edge(4, 3, 4, 6, 60, PathwayStatus.OPEN),
                edge(5, 2, 3, 1, 10, PathwayStatus.OPEN));
        CampusGraph g1 = new CampusGraph(nodes, edges);

        for (AlgorithmCase alg : algorithms()) {
            RouteResult r = alg.run(g1, 1, 4, CostMetric.DISTANCE);
            check(alg.name + ": A->D is A-C-B-D (not the obvious A-B-D)", r.isFound()
                    && r.getLocationIds().equals(Arrays.asList(1, 3, 2, 4)), r.toString());
            check(alg.name + ": A->D total distance is 6.0 m and time is 60 s",
                    Math.abs(r.getTotalDistance() - 6.0) < EPS && r.getTotalTime() == 60,
                    r.getTotalDistance() + " m, " + r.getTotalTime() + " s");
            check(alg.name + ": route has 3 pathways and 2 intermediate locations",
                    r.getPathways().size() == 3 && r.getIntermediateCount() == 2, "");
            RouteResult back = alg.run(g1, 4, 1, CostMetric.DISTANCE);
            check(alg.name + ": reverse trip D->A is the same route backwards",
                    back.isFound() && back.getLocationIds().equals(Arrays.asList(4, 2, 3, 1))
                            && Math.abs(back.getTotalDistance() - 6.0) < EPS, back.toString());
        }

        // Graph 2: the two metrics can disagree.
        //   A-C direct: 10 m but 100 s (slow, for example stairs)
        //   A-B-C     : 6 + 6 = 12 m but 30 + 30 = 60 s
        List<Location> nodes2 = Arrays.asList(loc(1, 0, 0), loc(2, 5, 3), loc(3, 10, 0));
        List<Pathway> edges2 = Arrays.asList(
                edge(1, 1, 3, 10, 100, PathwayStatus.OPEN),
                edge(2, 1, 2, 6, 30, PathwayStatus.OPEN),
                edge(3, 2, 3, 6, 30, PathwayStatus.OPEN));
        CampusGraph g2 = new CampusGraph(nodes2, edges2);
        for (AlgorithmCase alg : algorithms()) {
            RouteResult byDistance = alg.run(g2, 1, 3, CostMetric.DISTANCE);
            RouteResult byTime = alg.run(g2, 1, 3, CostMetric.TIME);
            check(alg.name + ": minimising DISTANCE picks the direct path [1, 3] (10 m)",
                    byDistance.getLocationIds().equals(Arrays.asList(1, 3)) && Math.abs(byDistance.getTotalDistance() - 10) < EPS,
                    byDistance.toString());
            check(alg.name + ": minimising TIME picks [1, 2, 3] (60 s although 12 m)",
                    byTime.getLocationIds().equals(Arrays.asList(1, 2, 3)) && byTime.getTotalTime() == 60,
                    byTime.toString());
        }

        // Graph 3: plain shortest-path ignores pathway status (this is why Phase 6 exists).
        List<Location> nodes3 = Arrays.asList(loc(1, 0, 0), loc(2, 10, 0), loc(3, 5, 8));
        List<Pathway> edges3 = Arrays.asList(
                edge(1, 1, 2, 10, 100, PathwayStatus.BLOCKED),   // short but BLOCKED
                edge(2, 1, 3, 9, 90, PathwayStatus.OPEN),
                edge(3, 3, 2, 9, 90, PathwayStatus.OPEN));
        CampusGraph g3 = new CampusGraph(nodes3, edges3);
        for (AlgorithmCase alg : algorithms()) {
            RouteResult r = alg.run(g3, 1, 2, CostMetric.DISTANCE);
            check(alg.name + ": uses the blocked path and FLAGS it (usesBlockedPathway = true)",
                    r.getLocationIds().equals(Arrays.asList(1, 2)) && r.usesBlockedPathway(), r.toString());
        }
    }

    // =================================================================
    // PART B: special cases
    // =================================================================
    private static void partB_specialCases() {
        System.out.println("\n[B] Special cases");

        // 1-2 connected, 3 isolated, 4-5 connected (a second, separate island)
        List<Location> nodes = Arrays.asList(loc(1, 0, 0), loc(2, 10, 0), loc(3, 50, 50), loc(4, 90, 0), loc(5, 100, 0));
        List<Pathway> edges = Arrays.asList(
                edge(1, 1, 2, 10, 10, PathwayStatus.OPEN),
                edge(2, 4, 5, 10, 10, PathwayStatus.OPEN));
        CampusGraph g = new CampusGraph(nodes, edges);

        for (AlgorithmCase alg : algorithms()) {
            RouteResult same = alg.run(g, 2, 2, CostMetric.DISTANCE);
            check(alg.name + ": same source and destination -> found, route [2], 0 m, 0 s",
                    same.isFound() && same.getLocationIds().equals(Arrays.asList(2))
                            && same.getTotalDistance() == 0 && same.getTotalTime() == 0
                            && same.getPathways().isEmpty(), same.toString());

            RouteResult isolated = alg.run(g, 1, 3, CostMetric.DISTANCE);
            check(alg.name + ": isolated destination -> NOT found, with a message",
                    !isolated.isFound() && isolated.getMessage().startsWith("No route exists")
                            && isolated.getLocationIds().isEmpty(), isolated.toString());

            RouteResult otherIsland = alg.run(g, 1, 5, CostMetric.TIME);
            check(alg.name + ": destination on a separate island -> NOT found", !otherIsland.isFound(), otherIsland.toString());

            RouteResult badSource = alg.run(g, 99, 1, CostMetric.DISTANCE);
            check(alg.name + ": unknown source id 99 -> NOT found, message mentions the starting location",
                    !badSource.isFound() && badSource.getMessage().contains("starting location"), badSource.getMessage());

            RouteResult badDest = alg.run(g, 1, 99, CostMetric.DISTANCE);
            check(alg.name + ": unknown destination id 99 -> NOT found, message mentions the destination",
                    !badDest.isFound() && badDest.getMessage().contains("destination"), badDest.getMessage());

            RouteResult unknownSame = alg.run(g, 99, 99, CostMetric.DISTANCE);
            check(alg.name + ": unknown id used as both source and destination -> NOT found (no false success)",
                    !unknownSame.isFound(), unknownSame.getMessage());
        }

        CampusGraph empty = new CampusGraph(new ArrayList<Location>(), new ArrayList<Pathway>());
        check("Empty graph: Dijkstra and A* both answer 'not found' without crashing",
                !DIJKSTRA.findRoute(empty, 1, 2, CostMetric.DISTANCE).isFound()
                        && !ASTAR.findRoute(empty, 1, 2, CostMetric.DISTANCE).isFound(), "");
        check("A* heuristic scale of a graph with no pathways is 0 (A* then behaves like Dijkstra)",
                AStarAlgorithm.heuristicScale(empty, CostMetric.DISTANCE) == 0.0, "");
    }

    // =================================================================
    // PART C (+ D): the real campus from MySQL
    // =================================================================
    private static void partC_realCampus() {
        System.out.println("\n[C] Real campus graph from MySQL");
        CampusGraph g;
        try {
            g = CampusGraph.loadFromDatabase();
        } catch (SQLException | RuntimeException e) {
            check("Loading the campus graph", false, e.getMessage());
            return;
        }

        // Hand-checked example 1: Main Gate (1) -> Central Library (8)
        //   1-2 is 88.0 m / 73 s  and  2-8 is 114.7 m / 96 s   => 202.7 m, 169 s
        for (AlgorithmCase alg : algorithms()) {
            RouteResult r = alg.run(g, 1, 8, CostMetric.DISTANCE);
            check(alg.name + ": Main Gate -> Central Library = [1, 2, 8], 202.7 m, 169 s",
                    r.isFound() && r.getLocationIds().equals(Arrays.asList(1, 2, 8))
                            && Math.abs(r.getTotalDistance() - 202.7) < EPS && r.getTotalTime() == 169, r.toString());
        }
        // Hand-checked example 2: CS Block (6) -> Library (8). The direct 75.0 m path is BLOCKED.
        // A plain shortest-path search still takes it (the detour 6-5-2-8 would be 311.4 m).
        for (AlgorithmCase alg : algorithms()) {
            RouteResult r = alg.run(g, 6, 8, CostMetric.DISTANCE);
            check(alg.name + ": CS Block -> Library takes the 75.0 m path and reports usesBlockedPathway = true",
                    r.getLocationIds().equals(Arrays.asList(6, 8)) && Math.abs(r.getTotalDistance() - 75.0) < EPS
                            && r.usesBlockedPathway(), r.toString());
        }

        // Every pair against Floyd-Warshall
        List<Integer> ids = new ArrayList<>();
        for (Location l : g.getLocations()) ids.add(l.getId());

        for (CostMetric metric : CostMetric.values()) {
            double[][] best = floydWarshall(g, ids, metric);
            int pairs = 0, dijkstraWrong = 0, astarWrong = 0, invalidPaths = 0;
            long dijkstraWork = 0, astarWork = 0;

            for (int i = 0; i < ids.size(); i++) {
                for (int j = 0; j < ids.size(); j++) {
                    if (i == j) continue;
                    pairs++;
                    RouteResult d = DIJKSTRA.findRoute(g, ids.get(i), ids.get(j), metric);
                    RouteResult a = ASTAR.findRoute(g, ids.get(i), ids.get(j), metric);
                    if (!d.isFound() || Math.abs(d.getTotalCost() - best[i][j]) > 0.06) dijkstraWrong++;
                    if (!a.isFound() || Math.abs(a.getTotalCost() - best[i][j]) > 0.06) astarWrong++;
                    if (!isValidWalk(d) || !isValidWalk(a)) invalidPaths++;
                    dijkstraWork += d.getNodesExpanded();
                    astarWork += a.getNodesExpanded();
                }
            }
            check("[" + metric + "] Dijkstra matches Floyd-Warshall on all " + pairs + " pairs", dijkstraWrong == 0, dijkstraWrong + " wrong");
            check("[" + metric + "] A* matches Floyd-Warshall on all " + pairs + " pairs", astarWrong == 0, astarWrong + " wrong");
            check("[" + metric + "] every returned route is a real walk (consecutive locations joined by the listed pathways)",
                    invalidPaths == 0, invalidPaths + " invalid");
            System.out.println("        info: locations examined over all pairs - Dijkstra " + dijkstraWork + ", A* " + astarWork
                    + (astarWork < dijkstraWork ? "  (A* examined fewer)" : ""));

            // PART D: the heuristic must never be larger than the true remaining cost
            double scale = AStarAlgorithm.heuristicScale(g, metric);
            int tooBig = 0;
            for (int i = 0; i < ids.size(); i++) {
                for (int j = 0; j < ids.size(); j++) {
                    double h = AStarAlgorithm.heuristic(g, ids.get(i), ids.get(j), scale);
                    if (h > best[i][j] + 1e-9) tooBig++;
                }
            }
            check("[" + metric + "] A* heuristic never overestimates (scale " + String.format("%.4f", scale) + ", all "
                    + (ids.size() * ids.size()) + " pairs)", tooBig == 0, tooBig + " overestimates");
        }
    }

    // =================================================================
    // PART E: random graphs
    // =================================================================
    private static void partE_randomGraphs() {
        System.out.println("\n[E] 60 random graphs (fixed random seed, so the test is repeatable)");
        Random random = new Random(42);
        int graphsWithProblems = 0;
        int totalPairs = 0;

        for (int round = 0; round < 60; round++) {
            int n = 5 + random.nextInt(20);                       // 5 to 24 locations
            List<Location> nodes = new ArrayList<>();
            for (int i = 1; i <= n; i++) nodes.add(loc(i, random.nextInt(1000), random.nextInt(650)));

            List<Pathway> edges = new ArrayList<>();
            int pid = 1;
            // a random tree keeps most of the graph connected; extra edges add alternative routes.
            // Distances are random and NOT related to the coordinates, to stress the heuristic scale.
            for (int i = 2; i <= n; i++) {
                if (random.nextInt(10) == 0) continue;            // sometimes leave a location disconnected
                edges.add(randomEdge(pid++, i, 1 + random.nextInt(i - 1), random));
            }
            for (int k = 0; k < n; k++) {
                int a = 1 + random.nextInt(n), b = 1 + random.nextInt(n);
                if (a != b) edges.add(randomEdge(pid++, a, b, random));   // duplicates are ignored by CampusGraph
            }
            CampusGraph g = new CampusGraph(nodes, edges);

            List<Integer> ids = new ArrayList<>();
            for (Location l : g.getLocations()) ids.add(l.getId());
            boolean problem = false;
            for (CostMetric metric : CostMetric.values()) {
                double[][] best = floydWarshall(g, ids, metric);
                for (int i = 0; i < ids.size(); i++) {
                    for (int j = 0; j < ids.size(); j++) {
                        if (i == j) continue;
                        totalPairs++;
                        RouteResult d = DIJKSTRA.findRoute(g, ids.get(i), ids.get(j), metric);
                        RouteResult a = ASTAR.findRoute(g, ids.get(i), ids.get(j), metric);
                        boolean reachable = best[i][j] < Double.MAX_VALUE / 4;
                        if (reachable) {
                            if (!d.isFound() || !a.isFound()
                                    || Math.abs(d.getTotalCost() - best[i][j]) > 0.06
                                    || Math.abs(a.getTotalCost() - best[i][j]) > 0.06
                                    || !isValidWalk(d) || !isValidWalk(a)) problem = true;
                        } else if (d.isFound() || a.isFound()) {
                            problem = true;                          // claimed a route that cannot exist
                        }
                    }
                }
            }
            if (problem) graphsWithProblems++;
        }
        check("Dijkstra, A* and Floyd-Warshall agree on all " + totalPairs + " location pairs in 60 random graphs "
                + "(including unreachable pairs)", graphsWithProblems == 0, graphsWithProblems + " graphs with problems");
    }

    private static Pathway randomEdge(int id, int a, int b, Random random) {
        double distance = Math.round((1 + random.nextDouble() * 1500) * 10.0) / 10.0;   // 1.0 - 1500.0 m
        int seconds = Math.max(1, (int) Math.round(distance / (0.8 + random.nextDouble())));
        return new Pathway(id, Math.min(a, b), Math.max(a, b), distance, seconds,
                CrowdLevel.LOW, PathwayStatus.OPEN, true);
    }

    // =================================================================
    // Independent checker: Floyd-Warshall all-pairs shortest paths
    // (a completely different algorithm, so a bug in Dijkstra/A* cannot hide)
    // =================================================================
    private static double[][] floydWarshall(CampusGraph g, List<Integer> ids, CostMetric metric) {
        int n = ids.size();
        double INF = Double.MAX_VALUE / 4;
        double[][] d = new double[n][n];
        for (int i = 0; i < n; i++) {
            Arrays.fill(d[i], INF);
            d[i][i] = 0;
        }
        for (Pathway p : g.getPathways()) {
            int i = ids.indexOf(p.getSourceId());
            int j = ids.indexOf(p.getDestinationId());
            double c = metric.costOf(p);
            d[i][j] = Math.min(d[i][j], c);
            d[j][i] = Math.min(d[j][i], c);
        }
        for (int k = 0; k < n; k++)
            for (int i = 0; i < n; i++)
                for (int j = 0; j < n; j++)
                    if (d[i][k] + d[k][j] < d[i][j]) d[i][j] = d[i][k] + d[k][j];
        return d;
    }

    /** True if every pathway really joins the two neighbouring locations of the route. */
    private static boolean isValidWalk(RouteResult r) {
        if (!r.isFound()) return false;
        List<Integer> ids = r.getLocationIds();
        if (ids.get(0) != r.getSourceId() || ids.get(ids.size() - 1) != r.getDestinationId()) return false;
        if (r.getPathways().size() != ids.size() - 1) return false;
        for (int i = 0; i < r.getPathways().size(); i++) {
            Pathway p = r.getPathways().get(i);
            int from = ids.get(i), to = ids.get(i + 1);
            boolean joins = (p.getSourceId() == from && p.getDestinationId() == to)
                    || (p.getSourceId() == to && p.getDestinationId() == from);
            if (!joins) return false;
        }
        return true;
    }

    // ---------- small helper so the same test runs for both algorithms ----------
    private static abstract class AlgorithmCase {
        final String name;
        AlgorithmCase(String name) { this.name = name; }
        abstract RouteResult run(CampusGraph g, int from, int to, CostMetric metric);
    }

    private static List<AlgorithmCase> algorithms() {
        return Arrays.asList(
                new AlgorithmCase("Dijkstra") {
                    RouteResult run(CampusGraph g, int from, int to, CostMetric m) { return DIJKSTRA.findRoute(g, from, to, m); }
                },
                new AlgorithmCase("A*") {
                    RouteResult run(CampusGraph g, int from, int to, CostMetric m) { return ASTAR.findRoute(g, from, to, m); }
                });
    }
}
