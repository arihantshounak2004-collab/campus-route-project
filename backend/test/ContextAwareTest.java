import algorithm.ContextAwareRouter;
import algorithm.RouteEvaluation;
import graph.CampusGraph;
import model.CostMetric;
import model.CrowdLevel;
import model.Location;
import model.LocationType;
import model.Pathway;
import model.PathwayStatus;
import model.RouteResult;
import model.RoutingPreferences;
import service.RouteComparison;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * ContextAwareTest  (Phase 6)
 * ---------------------------
 * A - hand-calculated graphs (NO database): crowds, blocked, restricted, accessibility, time metric
 * B - the plain-language explanation of the comparison
 * C - input validation of RoutingPreferences
 * D - the real campus from MySQL: hand-checked routes + EVERY pair under 6 rule sets,
 *     compared with an independent brute-force (Floyd-Warshall) that re-implements the rules
 * E - 80 random graphs with random rules
 *
 * Run from the PROJECT ROOT:   java -cp "out;lib\*" ContextAwareTest
 * Parts A, B, C, E need no database.
 */
public class ContextAwareTest {

    private static int passed = 0;
    private static int failed = 0;
    private static final double EPS = 0.06;
    private static final ContextAwareRouter ROUTER = new ContextAwareRouter();

    private static void check(String name, boolean ok, String detail) {
        if (ok) { passed++; } else { failed++; }
        System.out.println("  " + (ok ? "PASS" : "FAIL") + "  " + name + (detail.isEmpty() ? "" : "  -> " + detail));
    }

    // ---------- helpers for hand-made graphs ----------
    private static Location loc(int id) {
        return new Location(id, "N" + id, LocationType.BUILDING, "test", id * 10, 10, true);
    }
    /** seconds = distance x 10, so hand calculations stay easy. */
    private static Pathway edge(int id, int a, int b, double dist, CrowdLevel crowd, PathwayStatus status, boolean accessible) {
        return new Pathway(id, a, b, dist, (int) Math.round(dist * 10), crowd, status, accessible);
    }
    private static Pathway edgeT(int id, int a, int b, double dist, int seconds, CrowdLevel crowd) {
        return new Pathway(id, a, b, dist, seconds, crowd, PathwayStatus.OPEN, true);
    }
    private static CampusGraph graph(int nodeCount, Pathway... edges) {
        List<Location> nodes = new ArrayList<>();
        for (int i = 1; i <= nodeCount; i++) nodes.add(loc(i));
        return new CampusGraph(nodes, Arrays.asList(edges));
    }

    public static void main(String[] args) {
        System.out.println("=== Phase 6: Context-aware routing tests ===");
        partA_handCalculated();
        partB_explanations();
        partC_validation();
        partD_realCampus();
        partE_randomGraphs();
        System.out.println("\n=== Result: " + passed + " passed, " + failed + " failed ===");
        System.exit(failed == 0 ? 0 : 1);
    }

    // =================================================================
    // PART A
    // =================================================================
    private static void partA_handCalculated() {
        System.out.println("\n[A] Hand-calculated graphs");
        RoutingPreferences def = RoutingPreferences.defaults();
        CrowdLevel L = CrowdLevel.LOW, M = CrowdLevel.MEDIUM, H = CrowdLevel.HIGH;
        PathwayStatus OPEN = PathwayStatus.OPEN;

        // ----- A1: crowded short route vs calm longer route -----
        //   A-B-D : 10 + 10 = 20 m, both HIGH  -> 20 + 8 + 8 = 36
        //   A-C-D : 12 + 12 = 24 m, both LOW   -> 24 + 0 + 0 = 24   <- cheaper in context
        CampusGraph g1 = graph(4,
                edge(1, 1, 2, 10, H, OPEN, true), edge(2, 2, 4, 10, H, OPEN, true),
                edge(3, 1, 3, 12, L, OPEN, true), edge(4, 3, 4, 12, L, OPEN, true));
        RouteResult r1 = ROUTER.findRoute(g1, 1, 4, def);
        check("Crowds: picks the calm 24 m route [1,3,4] over the crowded 20 m route",
                r1.getLocationIds().equals(Arrays.asList(1, 3, 4)) && Math.abs(r1.getTotalDistance() - 24.0) < EPS, r1.toString());
        check("Crowds: contextual cost is 24.0 (24 m + 0 + 0)", Math.abs(r1.getContextualCost() - 24.0) < EPS, "" + r1.getContextualCost());
        RouteComparison c1 = RouteComparison.compare(g1, 1, 4, def);
        check("Crowds: the plain shortest route is [1,2,4] and its contextual cost is 36.0 (20 + 8 + 8)",
                c1.getShortest().getLocationIds().equals(Arrays.asList(1, 2, 4))
                        && Math.abs(c1.getShortestEvaluation().getContextualCost() - 36.0) < EPS,
                c1.getShortest() + " / " + c1.getShortestEvaluation().getContextualCost());

        // ----- A2: the detour is too long, so the crowded route is kept (penalties are limited) -----
        //   crowded 20 + 16 = 36   versus   calm 20 + 20 = 40
        CampusGraph g2 = graph(4,
                edge(1, 1, 2, 10, H, OPEN, true), edge(2, 2, 4, 10, H, OPEN, true),
                edge(3, 1, 3, 20, L, OPEN, true), edge(4, 3, 4, 20, L, OPEN, true));
        RouteResult r2 = ROUTER.findRoute(g2, 1, 4, def);
        check("Crowds: a detour that costs more than the penalty is NOT taken (keeps [1,2,4], cost 36.0)",
                r2.getLocationIds().equals(Arrays.asList(1, 2, 4)) && Math.abs(r2.getContextualCost() - 36.0) < EPS, r2.toString());

        // crowd weight 0 = ignore crowds, so A1 becomes the plain shortest route again
        RouteResult r1w0 = ROUTER.findRoute(g1, 1, 4, RoutingPreferences.defaults().setCrowdWeight(0));
        check("Crowd weight 0 ignores crowds: A1 gives [1,2,4] with cost 20.0",
                r1w0.getLocationIds().equals(Arrays.asList(1, 2, 4)) && Math.abs(r1w0.getContextualCost() - 20.0) < EPS, r1w0.toString());
        // a larger weight makes the detour of A2 worthwhile: crowded 20 + 5x16 = 100, calm 40
        RouteResult r2w5 = ROUTER.findRoute(g2, 1, 4, RoutingPreferences.defaults().setCrowdWeight(5));
        check("Crowd weight 5 makes the 40 m calm route win in A2 (cost 40.0 vs 100.0)",
                r2w5.getLocationIds().equals(Arrays.asList(1, 3, 4)) && Math.abs(r2w5.getContextualCost() - 40.0) < EPS, r2w5.toString());

        // ----- A3: the MEDIUM penalty is exactly 3 (boundary tests) -----
        //   medium route: 10 + 10 + 3 + 3 = 26
        CampusGraph g3a = graph(4,   // calm alternative 12.5 + 12.5 = 25 < 26  -> alternative wins
                edge(1, 1, 2, 10, M, OPEN, true), edge(2, 2, 4, 10, M, OPEN, true),
                edge(3, 1, 3, 12.5, L, OPEN, true), edge(4, 3, 4, 12.5, L, OPEN, true));
        CampusGraph g3b = graph(4,   // calm alternative 13.5 + 13.5 = 27 > 26 -> medium route stays
                edge(1, 1, 2, 10, M, OPEN, true), edge(2, 2, 4, 10, M, OPEN, true),
                edge(3, 1, 3, 13.5, L, OPEN, true), edge(4, 3, 4, 13.5, L, OPEN, true));
        check("MEDIUM penalty is 3 per segment: alternative of 25 beats 26",
                ROUTER.findRoute(g3a, 1, 4, def).getLocationIds().equals(Arrays.asList(1, 3, 4)), "");
        check("MEDIUM penalty is 3 per segment: alternative of 27 loses to 26",
                ROUTER.findRoute(g3b, 1, 4, def).getLocationIds().equals(Arrays.asList(1, 2, 4)), "");

        // ----- A4: BLOCKED paths are never used -----
        //   A-D direct 5 m BLOCKED;  A-C-D 10 + 10 = 20 m
        CampusGraph g4 = graph(4,
                edge(1, 1, 4, 5, L, PathwayStatus.BLOCKED, true),
                edge(2, 1, 3, 10, L, OPEN, true), edge(3, 3, 4, 10, L, OPEN, true));
        RouteResult r4 = ROUTER.findRoute(g4, 1, 4, def);
        check("Blocked: avoids the 5 m blocked path and returns [1,3,4], 20.0 m",
                r4.getLocationIds().equals(Arrays.asList(1, 3, 4)) && Math.abs(r4.getTotalDistance() - 20.0) < EPS && !r4.usesBlockedPathway(), r4.toString());
        RouteComparison c4 = RouteComparison.compare(g4, 1, 4, def);
        check("Blocked: the plain shortest route DOES use the blocked path, and is NOT recommendable",
                c4.getShortest().usesBlockedPathway() && !c4.getShortestEvaluation().isRecommendable()
                        && !c4.getShortestEvaluation().hasContextualCost(), c4.getShortest().toString());
        check("Blocked: the recommended route is the context-aware one", c4.getRecommended() != null
                && c4.getRecommended().getLocationIds().equals(Arrays.asList(1, 3, 4)), "");

        // ----- A5: the ONLY route is blocked -> no route (never a forbidden fallback) -----
        CampusGraph g5 = graph(2, edge(1, 1, 2, 5, L, PathwayStatus.BLOCKED, true));
        RouteResult r5 = ROUTER.findRoute(g5, 1, 2, def);
        check("Blocked only route: NOT found, message explains it uses a BLOCKED pathway",
                !r5.isFound() && r5.getMessage().startsWith("No allowed route exists") && r5.getMessage().contains("BLOCKED"), r5.getMessage());
        check("Blocked only route: the result has no locations and no pathways", r5.getLocationIds().isEmpty() && r5.getPathways().isEmpty(), "");
        RouteComparison c5 = RouteComparison.compare(g5, 1, 2, def);
        check("Blocked only route: nothing is recommended (getRecommended() = null)", c5.getRecommended() == null, "");

        // ----- A6: RESTRICTED paths -----
        //   A-D restricted 5 m;  A-C-D 20 m
        CampusGraph g6 = graph(4,
                edge(1, 1, 4, 5, L, PathwayStatus.RESTRICTED, true),
                edge(2, 1, 3, 10, L, OPEN, true), edge(3, 3, 4, 10, L, OPEN, true));
        RouteResult r6a = ROUTER.findRoute(g6, 1, 4, def);
        check("Restricted: excluded by default -> [1,3,4], 20.0 m", r6a.getLocationIds().equals(Arrays.asList(1, 3, 4)), r6a.toString());
        RouteResult r6b = ROUTER.findRoute(g6, 1, 4, RoutingPreferences.defaults().setAllowRestricted(true).setRestrictedPenalty(10));
        check("Restricted: permitted + penalty 10 -> direct path, distance 5.0, contextual cost 15.0 (5 + 10)",
                r6b.getLocationIds().equals(Arrays.asList(1, 4)) && Math.abs(r6b.getTotalDistance() - 5.0) < EPS
                        && Math.abs(r6b.getContextualCost() - 15.0) < EPS, r6b.toString() + " cost " + r6b.getContextualCost());
        RouteResult r6c = ROUTER.findRoute(g6, 1, 4, RoutingPreferences.defaults().setAllowRestricted(true).setRestrictedPenalty(100));
        check("Restricted: permitted but penalty 100 -> still prefers [1,3,4] (cost 20.0 < 105.0)",
                r6c.getLocationIds().equals(Arrays.asList(1, 3, 4)), r6c.toString());
        RouteResult r6d = ROUTER.findRoute(graph(2, edge(1, 1, 2, 5, L, PathwayStatus.RESTRICTED, true)), 1, 2, def);
        check("Restricted only route, not permitted: NOT found and the message says RESTRICTED",
                !r6d.isFound() && r6d.getMessage().contains("RESTRICTED"), r6d.getMessage());

        // ----- A7: accessibility -----
        //   A-D 5 m with steps (not accessible);  A-C-D 20 m accessible
        CampusGraph g7 = graph(4,
                edge(1, 1, 4, 5, L, OPEN, false),
                edge(2, 1, 3, 10, L, OPEN, true), edge(3, 3, 4, 10, L, OPEN, true));
        check("Accessibility not required: takes the 5 m path with steps",
                ROUTER.findRoute(g7, 1, 4, def).getLocationIds().equals(Arrays.asList(1, 4)), "");
        RouteResult r7 = ROUTER.findRoute(g7, 1, 4, RoutingPreferences.defaults().setRequireAccessible(true));
        check("Accessibility required: takes [1,3,4], 20.0 m, and uses no inaccessible pathway",
                r7.getLocationIds().equals(Arrays.asList(1, 3, 4)) && !new RouteEvaluation(r7, RoutingPreferences.defaults().setRequireAccessible(true)).getViolations().iterator().hasNext()
                        && !r7.usesInaccessiblePathway(), r7.toString());
        RouteResult r7b = ROUTER.findRoute(graph(2, edge(1, 1, 2, 5, L, OPEN, false)), 1, 2,
                RoutingPreferences.defaults().setRequireAccessible(true));
        check("Accessibility required but no accessible route: NOT found, message mentions 'not accessible'",
                !r7b.isFound() && r7b.getMessage().contains("not accessible"), r7b.getMessage());

        // ----- A8: the metric changes the outcome (penalties are in the unit being minimised) -----
        //   direct : 10 m, 50 s, HIGH
        //   detour : 12 + 12 = 24 m, 28 + 28 = 56 s, LOW
        //   DISTANCE: direct 10 + 8 = 18   vs detour 24     -> direct
        //   TIME    : direct 50 + 8 = 58   vs detour 56     -> detour
        CampusGraph g8 = graph(3,
                edgeT(1, 1, 3, 10, 50, H), edgeT(2, 1, 2, 12, 28, L), edgeT(3, 2, 3, 12, 28, L));
        RouteResult r8d = ROUTER.findRoute(g8, 1, 3, RoutingPreferences.defaults().setMetric(CostMetric.DISTANCE));
        RouteResult r8t = ROUTER.findRoute(g8, 1, 3, RoutingPreferences.defaults().setMetric(CostMetric.TIME));
        check("Metric DISTANCE: direct route wins, contextual cost 18.0", r8d.getLocationIds().equals(Arrays.asList(1, 3))
                && Math.abs(r8d.getContextualCost() - 18.0) < EPS, r8d.toString() + " cost " + r8d.getContextualCost());
        check("Metric TIME: detour wins, contextual cost 56.0 (penalties are in seconds)", r8t.getLocationIds().equals(Arrays.asList(1, 2, 3))
                && Math.abs(r8t.getContextualCost() - 56.0) < EPS, r8t.toString() + " cost " + r8t.getContextualCost());

        // ----- A9: special cases -----
        RouteResult same = ROUTER.findRoute(g1, 2, 2, def);
        check("Same source and destination: found, route [2], 0 m, contextual cost 0.0",
                same.isFound() && same.getLocationIds().equals(Arrays.asList(2)) && same.getTotalDistance() == 0 && same.getContextualCost() == 0.0, same.toString());
        RouteResult unknownFrom = ROUTER.findRoute(g1, 99, 1, def);
        RouteResult unknownTo = ROUTER.findRoute(g1, 1, 99, def);
        check("Unknown source / destination: NOT found with a clear message",
                !unknownFrom.isFound() && unknownFrom.getMessage().contains("starting location")
                        && !unknownTo.isFound() && unknownTo.getMessage().contains("destination"), unknownFrom.getMessage());
        check("Unknown id used twice (99 -> 99): NOT found (no false success)", !ROUTER.findRoute(g1, 99, 99, def).isFound(), "");
        RouteResult island = ROUTER.findRoute(graph(3, edge(1, 1, 2, 5, L, OPEN, true)), 1, 3, def);
        check("Disconnected location: NOT found, message 'No route exists' (nothing to do with the rules)",
                !island.isFound() && island.getMessage().startsWith("No route exists"), island.getMessage());

        // ----- A10: the evaluator -----
        Pathway blocked = edge(1, 1, 2, 5, L, PathwayStatus.BLOCKED, true);
        RouteResult manual = RouteResult.found("manual", CostMetric.DISTANCE, 1, 2, Arrays.asList(1, 2), Arrays.asList(blocked), 0, "");
        RouteEvaluation ev = new RouteEvaluation(manual, def);
        check("Evaluator: a route over a BLOCKED pathway has 1 violation, no contextual cost, is not recommendable",
                ev.getViolations().size() == 1 && !ev.hasContextualCost() && !ev.isRecommendable() && ev.getBlockedSegments() == 1, ev.getViolations().toString());
        Pathway restricted = edge(2, 1, 2, 5, M, PathwayStatus.RESTRICTED, true);
        RouteResult manual2 = RouteResult.found("manual", CostMetric.DISTANCE, 1, 2, Arrays.asList(1, 2), Arrays.asList(restricted), 0, "");
        check("Evaluator: restricted path is a violation by default",
                !new RouteEvaluation(manual2, def).isRecommendable(), "");
        RouteEvaluation ok = new RouteEvaluation(manual2, RoutingPreferences.defaults().setAllowRestricted(true));
        check("Evaluator: restricted path is fine when permitted; cost = 5 + 3 (MEDIUM) + 15 (restricted) = 23.0",
                ok.isRecommendable() && Math.abs(ok.getContextualCost() - 23.0) < EPS, "" + ok.getContextualCost());
    }

    // =================================================================
    // PART B: explanations
    // =================================================================
    private static void partB_explanations() {
        System.out.println("\n[B] Plain-language explanation");
        RoutingPreferences def = RoutingPreferences.defaults();
        CrowdLevel L = CrowdLevel.LOW, H = CrowdLevel.HIGH;
        PathwayStatus OPEN = PathwayStatus.OPEN;

        CampusGraph crowd = graph(4,
                edge(1, 1, 2, 10, H, OPEN, true), edge(2, 2, 4, 10, H, OPEN, true),
                edge(3, 1, 3, 12, L, OPEN, true), edge(4, 3, 4, 12, L, OPEN, true));
        String text = String.join(" | ", RouteComparison.compare(crowd, 1, 4, def).getExplanation());
        check("Crowd case mentions avoiding 2 heavily crowded pathways", text.contains("2 fewer heavily crowded pathways"), text);
        check("Crowd case states the cost of the detour: '4.0 m longer (20% more)' and '40 s more'",
                text.contains("4.0 m longer (20% more)") && text.contains("40 s more"), text);

        CampusGraph blocked = graph(4,
                edge(1, 1, 4, 5, L, PathwayStatus.BLOCKED, true),
                edge(2, 1, 3, 10, L, OPEN, true), edge(3, 3, 4, 10, L, OPEN, true));
        String t2 = String.join(" | ", RouteComparison.compare(blocked, 1, 4, def).getExplanation());
        check("Blocked case says the shortest route is not allowed because of the BLOCKED pathway",
                t2.contains("shortest route is not allowed") && t2.contains("BLOCKED"), t2);

        CampusGraph keep = graph(4,
                edge(1, 1, 2, 10, H, OPEN, true), edge(2, 2, 4, 10, H, OPEN, true),
                edge(3, 1, 3, 20, L, OPEN, true), edge(4, 3, 4, 20, L, OPEN, true));
        RouteComparison same = RouteComparison.compare(keep, 1, 4, def);
        String t3 = String.join(" | ", same.getExplanation());
        check("When the routes are the same, the explanation says so (no invented difference)",
                same.contextRouteSameAsShortest() && t3.contains("both are the same") && t3.contains("2 heavily crowded pathways"), t3);

        CampusGraph none = graph(2, edge(1, 1, 2, 5, L, PathwayStatus.BLOCKED, true));
        List<String> t4 = RouteComparison.compare(none, 1, 2, def).getExplanation();
        check("No allowed route: first line is the router's message, second says the shortest route is NOT recommended",
                t4.size() == 2 && t4.get(0).startsWith("No allowed route exists") && t4.get(1).contains("NOT recommended"), t4.toString());

        CampusGraph accessible = graph(4, edge(1, 1, 4, 5, L, OPEN, false),
                edge(2, 1, 3, 10, L, OPEN, true), edge(3, 3, 4, 10, L, OPEN, true));
        String t5 = String.join(" | ", RouteComparison.compare(accessible, 1, 4, RoutingPreferences.defaults().setRequireAccessible(true)).getExplanation());
        check("Accessibility case says the shortest route uses a pathway that is not accessible",
                t5.contains("not accessible"), t5);

        CampusGraph restricted = graph(2, edge(1, 1, 2, 5, L, PathwayStatus.RESTRICTED, true));
        String t6 = String.join(" | ", RouteComparison.compare(restricted, 1, 2, RoutingPreferences.defaults().setAllowRestricted(true)).getExplanation());
        check("A permitted restricted pathway is NOT described as 'no restricted pathways'",
                t6.contains("1 restricted pathway") && !t6.contains("no blocked or restricted"), t6);

        check("Same place: explanation is 'already at your destination'",
                RouteComparison.compare(crowd, 3, 3, def).getExplanation().get(0).contains("already at your destination"), "");
    }

    // =================================================================
    // PART C: RoutingPreferences validation
    // =================================================================
    private static void partC_validation() {
        System.out.println("\n[C] RoutingPreferences rejects bad values");
        check("Negative crowd penalty is rejected", throwsIAE(() -> RoutingPreferences.defaults().setCrowdPenalties(-1, 3, 8)), "");
        check("Penalties that decrease (LOW > MEDIUM) are rejected", throwsIAE(() -> RoutingPreferences.defaults().setCrowdPenalties(5, 3, 8)), "");
        check("NaN crowd weight is rejected", throwsIAE(() -> RoutingPreferences.defaults().setCrowdWeight(Double.NaN)), "");
        check("Infinite restricted penalty is rejected", throwsIAE(() -> RoutingPreferences.defaults().setRestrictedPenalty(Double.POSITIVE_INFINITY)), "");
        check("Null metric is rejected", throwsIAE(() -> RoutingPreferences.defaults().setMetric(null)), "");
        RoutingPreferences d = RoutingPreferences.defaults();
        check("Defaults are LOW 0, MEDIUM 3, HIGH 8 (crowd weight 1)",
                d.crowdPenalty(CrowdLevel.LOW) == 0 && d.crowdPenalty(CrowdLevel.MEDIUM) == 3 && d.crowdPenalty(CrowdLevel.HIGH) == 8, "");
        check("Crowd weight multiplies all penalties (weight 2: 0 / 6 / 16)",
                RoutingPreferences.defaults().setCrowdWeight(2).crowdPenalty(CrowdLevel.MEDIUM) == 6
                        && RoutingPreferences.defaults().setCrowdWeight(2).crowdPenalty(CrowdLevel.HIGH) == 16, "");
    }

    private static boolean throwsIAE(Runnable r) {
        try {
            r.run();
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    // =================================================================
    // PART D: the real campus
    // =================================================================
    private static void partD_realCampus() {
        System.out.println("\n[D] Real campus graph from MySQL");
        CampusGraph g;
        try {
            g = CampusGraph.loadFromDatabase();
        } catch (SQLException | RuntimeException e) {
            check("Loading the campus graph", false, e.getMessage());
            return;
        }
        RoutingPreferences def = RoutingPreferences.defaults();

        // Hand-checked example 1: CS Block (6) -> Library (8). The 75.0 m path is BLOCKED.
        //   detour 6-7 (105.0) + 7-9 (88.0) + 9-8 (85.0) = 278.0 m; 8-9 is MEDIUM (+3) => 281.0
        RouteComparison c1 = RouteComparison.compare(g, 6, 8, def);
        RouteResult x1 = c1.getContextAware();
        check("CS Block -> Library: avoids the blocked path, route [6,7,9,8], 278.0 m, contextual cost 281.0",
                x1.getLocationIds().equals(Arrays.asList(6, 7, 9, 8)) && Math.abs(x1.getTotalDistance() - 278.0) < EPS
                        && Math.abs(x1.getContextualCost() - 281.0) < EPS, x1 + " cost " + x1.getContextualCost());
        check("CS Block -> Library: plain shortest [6,8] 75.0 m is flagged NOT recommendable",
                c1.getShortest().getLocationIds().equals(Arrays.asList(6, 8)) && !c1.getShortestEvaluation().isRecommendable(), "");
        check("CS Block -> Library: explanation says '203.0 m longer (271% more)'",
                String.join(" ", c1.getExplanation()).contains("203.0 m longer (271% more)"), String.join(" | ", c1.getExplanation()));

        // Hand-checked example 2: Electrical Block (5) -> CS Block (6). Direct path 5-6 has steps.
        //   accessible route 5-2 (89.9) 2-8 (114.7) 8-9 (85.0) 9-7 (88.0) 7-6 (105.0) = 482.6 m
        //   crowds: 2-8 MEDIUM +3, 8-9 MEDIUM +3, others LOW => 488.6
        check("Electrical -> CS Block, no accessibility rule: direct [5,6] 106.8 m",
                ROUTER.findRoute(g, 5, 6, def).getLocationIds().equals(Arrays.asList(5, 6)), "");
        RoutingPreferences acc = RoutingPreferences.defaults().setRequireAccessible(true);
        RouteResult x2 = ROUTER.findRoute(g, 5, 6, acc);
        check("Electrical -> CS Block, accessibility required: [5,2,8,9,7,6], 482.6 m, contextual cost 488.6",
                x2.getLocationIds().equals(Arrays.asList(5, 2, 8, 9, 7, 6)) && Math.abs(x2.getTotalDistance() - 482.6) < EPS
                        && Math.abs(x2.getContextualCost() - 488.6) < EPS && !x2.usesInaccessiblePathway(), x2 + " cost " + x2.getContextualCost());

        // Hand-checked example 3: Medical Centre (11) -> Boys Hostel (13). The direct path is RESTRICTED.
        //   11-14 (99.2) + 14-13 (112.9) = 212.1 m, both LOW.   Direct 137.6 m + restricted penalty 15 = 152.6
        RouteResult x3 = ROUTER.findRoute(g, 11, 13, def);
        check("Medical -> Boys Hostel: restricted path excluded, route [11,14,13], 212.1 m",
                x3.getLocationIds().equals(Arrays.asList(11, 14, 13)) && Math.abs(x3.getTotalDistance() - 212.1) < EPS, x3.toString());
        RouteResult x3b = ROUTER.findRoute(g, 11, 13, RoutingPreferences.defaults().setAllowRestricted(true));
        check("Medical -> Boys Hostel with restricted permitted: [11,13], 137.6 m, contextual cost 152.6 (137.6 + 15)",
                x3b.getLocationIds().equals(Arrays.asList(11, 13)) && Math.abs(x3b.getContextualCost() - 152.6) < EPS, x3b + " cost " + x3b.getContextualCost());

        // Hand-checked example 4: a pair where CROWDS alone change the route.
        //   shortest 1-2-8-9-7 = 88.0+114.7+85.0+88.0 = 375.7 m, crowd 8+3+3+0 = 14  => 389.7
        //   context  1-4-5-6-7 = 88.2+89.4+106.8+105.0 = 389.4 m, all LOW            => 389.4
        RouteComparison c4 = RouteComparison.compare(g, 1, 7, def);
        check("Main Gate -> Mechanical Block: shortest [1,2,8,9,7] (375.7 m) costs 389.7 in context",
                c4.getShortest().getLocationIds().equals(Arrays.asList(1, 2, 8, 9, 7))
                        && Math.abs(c4.getShortest().getTotalDistance() - 375.7) < EPS
                        && Math.abs(c4.getShortestEvaluation().getContextualCost() - 389.7) < EPS, c4.getShortest().toString());
        check("Main Gate -> Mechanical Block: context-aware [1,4,5,6,7] (389.4 m) is cheaper at 389.4, although 13.7 m longer",
                c4.getContextAware().getLocationIds().equals(Arrays.asList(1, 4, 5, 6, 7))
                        && Math.abs(c4.getContextAware().getContextualCost() - 389.4) < EPS, c4.getContextAware().toString());
        check("Main Gate -> Mechanical Block with crowd weight 0: the routes are the same again",
                RouteComparison.compare(g, 1, 7, RoutingPreferences.defaults().setCrowdWeight(0)).contextRouteSameAsShortest(), "");

        // Exhaustive comparison with an independent brute-force method
        List<Integer> ids = new ArrayList<>();
        for (Location l : g.getLocations()) ids.add(l.getId());

        List<Scenario> scenarios = Arrays.asList(
                new Scenario("default (distance)", false, false, false, 0, 3, 8, 1, 15),
                new Scenario("time metric", true, false, false, 0, 3, 8, 1, 15),
                new Scenario("accessibility required", false, true, false, 0, 3, 8, 1, 15),
                new Scenario("restricted permitted", false, false, true, 0, 3, 8, 1, 15),
                new Scenario("crowd weight 3", false, false, false, 0, 3, 8, 3, 15),
                new Scenario("everything on, time, custom penalties", true, true, true, 1, 5, 20, 2, 7));

        for (Scenario sc : scenarios) {
            RoutingPreferences prefs = sc.prefs();
            double[][] best = floydWarshall(g, ids, sc);
            int pairs = 0, wrongCost = 0, wrongReach = 0, violations = 0, invalidWalk = 0, worseThanShortest = 0, recommendedMismatch = 0;
            int crowdOnlyDifferences = 0;
            for (int i = 0; i < ids.size(); i++) {
                for (int j = 0; j < ids.size(); j++) {
                    if (i == j) continue;
                    pairs++;
                    RouteResult r = ROUTER.findRoute(g, ids.get(i), ids.get(j), prefs);
                    boolean reachable = best[i][j] < INF / 4;
                    if (r.isFound() != reachable) { wrongReach++; continue; }
                    if (!r.isFound()) continue;
                    if (Math.abs(r.getContextualCost() - best[i][j]) > EPS) wrongCost++;
                    if (!new RouteEvaluation(r, prefs).getViolations().isEmpty()) violations++;
                    if (r.usesBlockedPathway()) violations++;
                    if (!isValidWalk(r)) invalidWalk++;
                    RouteComparison c = RouteComparison.compare(g, ids.get(i), ids.get(j), prefs);
                    if (c.getRecommended() == null) recommendedMismatch++;
                    if (c.getShortestEvaluation().isRecommendable()
                            && r.getContextualCost() > c.getShortestEvaluation().getContextualCost() + EPS) worseThanShortest++;
                    if (c.getShortestEvaluation().isRecommendable() && !c.contextRouteSameAsShortest()) crowdOnlyDifferences++;
                }
            }
            String label = "[" + sc.name + "] ";
            check(label + "found/not-found agrees with brute force on all " + pairs + " pairs", wrongReach == 0, wrongReach + " wrong");
            check(label + "contextual cost equals the brute-force minimum on every pair", wrongCost == 0, wrongCost + " wrong");
            check(label + "no route ever breaks a hard rule (blocked / restricted / accessibility)", violations == 0, violations + " violations");
            check(label + "every route is a real walk, and every found route is recommended", invalidWalk == 0 && recommendedMismatch == 0,
                    invalidWalk + " invalid, " + recommendedMismatch + " not recommended");
            check(label + "context route is never costlier than an allowed shortest route", worseThanShortest == 0, worseThanShortest + " worse");
            System.out.println("        info: pairs where an allowed shortest route is replaced by a different route: " + crowdOnlyDifferences);
        }
    }

    // =================================================================
    // PART E: random graphs with random statuses and random rules
    // =================================================================
    private static void partE_randomGraphs() {
        System.out.println("\n[E] 80 random graphs with random statuses, crowds, accessibility and rules (fixed seed)");
        Random random = new Random(2024);
        int problems = 0, totalPairs = 0, notFoundPairs = 0;

        for (int round = 0; round < 80; round++) {
            int n = 4 + random.nextInt(18);
            List<Location> nodes = new ArrayList<>();
            for (int i = 1; i <= n; i++) nodes.add(loc(i));
            List<Pathway> edges = new ArrayList<>();
            int pid = 1;
            for (int i = 2; i <= n; i++) {
                if (random.nextInt(12) == 0) continue;
                edges.add(randomEdge(pid++, 1 + random.nextInt(i - 1), i, random));
            }
            for (int k = 0; k < n + 3; k++) {
                int a = 1 + random.nextInt(n), b = 1 + random.nextInt(n);
                if (a != b) edges.add(randomEdge(pid++, Math.min(a, b), Math.max(a, b), random));
            }
            CampusGraph g = new CampusGraph(nodes, edges);

            double low = random.nextInt(2), med = low + random.nextInt(5), high = med + random.nextInt(12);
            Scenario sc = new Scenario("random", random.nextBoolean(), random.nextBoolean(), random.nextBoolean(),
                    low, med, high, new double[] { 0, 1, 2, 5 }[random.nextInt(4)], random.nextInt(25));
            RoutingPreferences prefs = sc.prefs();

            List<Integer> ids = new ArrayList<>();
            for (Location l : g.getLocations()) ids.add(l.getId());
            double[][] best = floydWarshall(g, ids, sc);
            boolean bad = false;
            for (int i = 0; i < ids.size(); i++) {
                for (int j = 0; j < ids.size(); j++) {
                    if (i == j) continue;
                    totalPairs++;
                    RouteResult r = ROUTER.findRoute(g, ids.get(i), ids.get(j), prefs);
                    boolean reachable = best[i][j] < INF / 4;
                    if (!reachable) notFoundPairs++;
                    if (r.isFound() != reachable) { bad = true; continue; }
                    if (r.isFound()) {
                        if (Math.abs(r.getContextualCost() - best[i][j]) > EPS || !isValidWalk(r)
                                || r.usesBlockedPathway()
                                || !new RouteEvaluation(r, prefs).getViolations().isEmpty()) bad = true;
                    }
                }
            }
            if (bad) problems++;
        }
        check("Router matches brute force on all " + totalPairs + " pairs in 80 random graphs ("
                + notFoundPairs + " pairs had no allowed route and were correctly 'not found')", problems == 0, problems + " graphs with problems");
    }

    private static Pathway randomEdge(int id, int a, int b, Random random) {
        double dist = Math.round((1 + random.nextDouble() * 400) * 10.0) / 10.0;
        int seconds = Math.max(1, (int) Math.round(dist / (0.8 + random.nextDouble())));
        double s = random.nextDouble();
        PathwayStatus status = s < 0.12 ? PathwayStatus.BLOCKED : (s < 0.24 ? PathwayStatus.RESTRICTED : PathwayStatus.OPEN);
        CrowdLevel crowd = CrowdLevel.values()[random.nextInt(3)];
        return new Pathway(id, a, b, dist, seconds, crowd, status, random.nextDouble() < 0.75);
    }

    // =================================================================
    // INDEPENDENT CHECKER
    // The rules are written out AGAIN here, straight from the project specification,
    // without calling RoutingPreferences, so a bug in the real rules cannot hide.
    // =================================================================
    private static final double INF = Double.MAX_VALUE;

    private static class Scenario {
        final String name;
        final boolean time, requireAccessible, allowRestricted;
        final double low, medium, high, weight, restrictedPenalty;

        Scenario(String name, boolean time, boolean requireAccessible, boolean allowRestricted,
                 double low, double medium, double high, double weight, double restrictedPenalty) {
            this.name = name; this.time = time; this.requireAccessible = requireAccessible;
            this.allowRestricted = allowRestricted; this.low = low; this.medium = medium;
            this.high = high; this.weight = weight; this.restrictedPenalty = restrictedPenalty;
        }

        RoutingPreferences prefs() {
            return RoutingPreferences.defaults()
                    .setMetric(time ? CostMetric.TIME : CostMetric.DISTANCE)
                    .setRequireAccessible(requireAccessible)
                    .setAllowRestricted(allowRestricted)
                    .setCrowdPenalties(low, medium, high)
                    .setCrowdWeight(weight)
                    .setRestrictedPenalty(restrictedPenalty);
        }

        /** The reference cost of one pathway, or INF if it is forbidden. */
        double reference(Pathway p) {
            if (p.getStatus() == PathwayStatus.BLOCKED) return INF;
            if (p.getStatus() == PathwayStatus.RESTRICTED && !allowRestricted) return INF;
            if (requireAccessible && !p.isAccessible()) return INF;
            double base = time ? p.getEstimatedTime() : p.getDistance();
            double crowd = p.getCrowdLevel() == CrowdLevel.LOW ? low : (p.getCrowdLevel() == CrowdLevel.MEDIUM ? medium : high);
            double cost = base + weight * crowd;
            if (p.getStatus() == PathwayStatus.RESTRICTED) cost += restrictedPenalty;
            return cost;
        }
    }

    private static double[][] floydWarshall(CampusGraph g, List<Integer> ids, Scenario sc) {
        int n = ids.size();
        double[][] d = new double[n][n];
        for (int i = 0; i < n; i++) {
            Arrays.fill(d[i], INF);
            d[i][i] = 0;
        }
        for (Pathway p : g.getPathways()) {
            double c = sc.reference(p);
            if (c >= INF) continue;
            int i = ids.indexOf(p.getSourceId()), j = ids.indexOf(p.getDestinationId());
            d[i][j] = Math.min(d[i][j], c);
            d[j][i] = Math.min(d[j][i], c);
        }
        for (int k = 0; k < n; k++)
            for (int i = 0; i < n; i++)
                for (int j = 0; j < n; j++)
                    if (d[i][k] < INF / 4 && d[k][j] < INF / 4 && d[i][k] + d[k][j] < d[i][j]) d[i][j] = d[i][k] + d[k][j];
        return d;
    }

    private static boolean isValidWalk(RouteResult r) {
        if (!r.isFound()) return false;
        List<Integer> ids = r.getLocationIds();
        if (ids.get(0) != r.getSourceId() || ids.get(ids.size() - 1) != r.getDestinationId()) return false;
        if (r.getPathways().size() != ids.size() - 1) return false;
        for (int i = 0; i < r.getPathways().size(); i++) {
            Pathway p = r.getPathways().get(i);
            int from = ids.get(i), to = ids.get(i + 1);
            if (!((p.getSourceId() == from && p.getDestinationId() == to) || (p.getSourceId() == to && p.getDestinationId() == from))) return false;
        }
        return true;
    }
}
