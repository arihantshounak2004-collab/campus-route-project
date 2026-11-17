package service;

import algorithm.AStarAlgorithm;
import algorithm.ContextAwareRouter;
import algorithm.DijkstraAlgorithm;
import algorithm.RouteEvaluation;
import graph.CampusGraph;
import model.RouteResult;
import model.RoutingPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * RouteComparison - runs the three routing methods for the same trip and compares them.
 *
 *   1. Shortest route  (Dijkstra)      - ignores crowds, closures, accessibility
 *   2. A* route                        - same cost as Dijkstra, found with a map-based guess
 *   3. Context-aware route             - follows the hard rules and avoids crowds
 *
 * The three routes are NOT always different. When they are the same, the explanation says so.
 * Only the context-aware route is ever "recommended", and only if it was found: a shortest route
 * that breaks a hard rule is shown for comparison but marked as NOT recommended.
 *
 * All numbers come from the server's own calculation, never from the client.
 */
public class RouteComparison {

    private final RoutingPreferences preferences;
    private final RouteResult shortest;
    private final RouteResult astar;
    private final RouteResult contextAware;
    private final RouteEvaluation shortestEvaluation;
    private final RouteEvaluation astarEvaluation;
    private final RouteEvaluation contextEvaluation;
    private final List<String> explanation;

    private RouteComparison(RoutingPreferences prefs, RouteResult shortest, RouteResult astar, RouteResult context) {
        this.preferences = prefs;
        this.shortest = shortest;
        this.astar = astar;
        this.contextAware = context;
        this.shortestEvaluation = new RouteEvaluation(shortest, prefs);
        this.astarEvaluation = new RouteEvaluation(astar, prefs);
        this.contextEvaluation = new RouteEvaluation(context, prefs);
        this.explanation = Collections.unmodifiableList(buildExplanation());
    }

    /** Runs all three algorithms and builds the comparison. */
    public static RouteComparison compare(CampusGraph graph, int sourceId, int destinationId, RoutingPreferences prefs) {
        RouteResult shortest = new DijkstraAlgorithm().findRoute(graph, sourceId, destinationId, prefs.getMetric());
        RouteResult astar = new AStarAlgorithm().findRoute(graph, sourceId, destinationId, prefs.getMetric());
        RouteResult context = new ContextAwareRouter().findRoute(graph, sourceId, destinationId, prefs);
        return new RouteComparison(prefs, shortest, astar, context);
    }

    public RoutingPreferences getPreferences()         { return preferences; }
    public RouteResult getShortest()                   { return shortest; }
    public RouteResult getAStar()                      { return astar; }
    public RouteResult getContextAware()               { return contextAware; }
    public RouteEvaluation getShortestEvaluation()     { return shortestEvaluation; }
    public RouteEvaluation getAStarEvaluation()        { return astarEvaluation; }
    public RouteEvaluation getContextEvaluation()      { return contextEvaluation; }
    public List<String> getExplanation()               { return explanation; }

    /** The route we recommend, or null if no allowed route exists. */
    public RouteResult getRecommended() {
        return contextEvaluation.isRecommendable() ? contextAware : null;
    }

    /** True if the context-aware route visits exactly the same locations as the shortest route. */
    public boolean contextRouteSameAsShortest() {
        return contextAware.isFound() && shortest.isFound()
                && contextAware.getLocationIds().equals(shortest.getLocationIds());
    }

    // ------------------------------------------------------------------
    // The plain-language explanation
    // ------------------------------------------------------------------
    private List<String> buildExplanation() {
        List<String> lines = new ArrayList<>();

        // Case 1: no allowed route
        if (!contextAware.isFound()) {
            lines.add(contextAware.getMessage());
            if (shortest.isFound() && !shortestEvaluation.isRecommendable()) {
                lines.add("The plain shortest route (" + shortest.getTotalDistance()
                        + " m) is NOT recommended because it " + String.join(" and ", shortestEvaluation.getViolations()) + ".");
            }
            return lines;
        }

        // Same place: nothing to compare
        if (contextAware.getLocationIds().size() == 1) {
            lines.add("You are already at your destination.");
            return lines;
        }

        // Case 2: the context-aware route is the same as the shortest route
        if (contextRouteSameAsShortest()) {
            // Describe EXACTLY what the route contains (a permitted restricted path must not be hidden).
            int restricted = shortestEvaluation.getRestrictedSegments();
            String rules = restricted > 0
                    ? "no blocked pathways, and " + restricted + " restricted pathway" + (restricted == 1 ? "" : "s")
                      + " that this user is permitted to use (counted with an extra penalty)"
                    : "no blocked or restricted pathways";
            if (preferences.isRequireAccessible()) {
                rules += ", and every pathway is accessible";
            }
            lines.add("The shortest route is also the best route right now, so both are the same. It uses " + rules + ".");
            if (shortestEvaluation.getHighCrowdSegments() > 0) {
                lines.add("It passes " + shortestEvaluation.getHighCrowdSegments() + " heavily crowded pathway"
                        + (shortestEvaluation.getHighCrowdSegments() == 1 ? "" : "s")
                        + ", but every alternative costs more once the crowd penalty is counted.");
            }
            return lines;
        }

        // Case 3: the routes differ - say why
        if (!shortest.isFound()) {
            lines.add("A route was found that follows the rules.");
            return lines;
        }
        if (!shortestEvaluation.isRecommendable()) {
            lines.add("The shortest route is not allowed: it " + String.join(" and ", shortestEvaluation.getViolations()) + ".");
        } else {
            int highAvoided = shortestEvaluation.getHighCrowdSegments() - contextEvaluation.getHighCrowdSegments();
            int mediumAvoided = shortestEvaluation.getMediumCrowdSegments() - contextEvaluation.getMediumCrowdSegments();
            if (highAvoided > 0 || mediumAvoided > 0) {
                StringBuilder sb = new StringBuilder("The recommended route avoids crowds: ");
                if (highAvoided > 0) sb.append(highAvoided).append(" fewer heavily crowded pathway").append(highAvoided == 1 ? "" : "s");
                if (highAvoided > 0 && mediumAvoided > 0) sb.append(" and ");
                if (mediumAvoided > 0) sb.append(mediumAvoided).append(" fewer moderately crowded pathway").append(mediumAvoided == 1 ? "" : "s");
                lines.add(sb.append(".").toString());
            } else {
                lines.add("The recommended route has the same contextual cost as the shortest route but takes different pathways.");
            }
        }

        double extraDistance = contextAware.getTotalDistance() - shortest.getTotalDistance();
        int extraTime = contextAware.getTotalTime() - shortest.getTotalTime();
        if (extraDistance > 0.05) {
            long percent = Math.round(extraDistance / shortest.getTotalDistance() * 100.0);
            lines.add("It is " + round1(extraDistance) + " m longer (" + percent + "% more) and takes about "
                    + Math.abs(extraTime) + " s " + (extraTime >= 0 ? "more" : "less")
                    + ", but it is the best route that follows the rules.");
        } else if (extraDistance < -0.05) {
            lines.add("It is even " + round1(-extraDistance) + " m shorter, because the shortest-time and shortest-distance "
                    + "routes can differ.");
        }
        return lines;
    }

    private static String round1(double v) {
        return String.valueOf(Math.round(v * 10.0) / 10.0);
    }
}
