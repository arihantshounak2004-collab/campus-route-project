package algorithm;

import graph.CampusGraph;
import graph.Edge;
import model.CostMetric;
import model.Pathway;
import model.RouteResult;
import model.RoutingPreferences;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * ContextAwareRouter - finds the best route that FOLLOWS THE RULES and avoids crowds.
 *
 * It is Dijkstra's algorithm with two changes:
 *
 *   1. EXCLUDE: a pathway that a hard rule forbids (blocked / restricted / not accessible)
 *      is skipped completely. Nothing can bring it back, so the router can never return a
 *      route that breaks a hard rule.
 *   2. REPRICE: the cost of every other pathway is  base cost + crowd penalty (+ restricted
 *      penalty).  See RoutingPreferences.segmentCost().
 *
 * Because all costs are still positive, Dijkstra remains correct: the result is the route
 * with the lowest CONTEXTUAL cost among all allowed routes.
 *
 * WHY THE CONTEXT-AWARE ROUTE CAN BE LONGER BUT BETTER
 *   The shortest route may cross a blocked path (impossible to walk), a restricted area
 *   (not allowed), steps (impossible for a wheelchair) or a very crowded corridor (slow and
 *   uncomfortable). The context-aware route accepts some extra walking to avoid those.
 *   It is NOT always different: if the shortest route is allowed and not worth avoiding,
 *   both routes are the same.
 *
 * IF NO ALLOWED ROUTE EXISTS the router says so (found = false) with an explanation.
 * It never "falls back" to a forbidden route.
 *
 * This class is deliberately separate from DijkstraAlgorithm, which stays a plain
 * shortest-path search.
 */
public class ContextAwareRouter {

    private static final String NAME = "Context-aware";

    private static class QueueEntry {
        final int locationId;
        final double cost;

        QueueEntry(int locationId, double cost) {
            this.locationId = locationId;
            this.cost = cost;
        }
    }

    public RouteResult findRoute(CampusGraph graph, int sourceId, int destinationId, RoutingPreferences prefs) {
        CostMetric metric = prefs.getMetric();

        RouteResult early = RouteChecks.checkInput(graph, sourceId, destinationId, NAME, metric);
        if (early != null) {
            // unknown location -> early is "not found"; same place -> early is "found" with 0 cost
            return early.isFound() ? early.withContextualCost(0, early.getMessage()) : early;
        }

        Map<Integer, Double> cost = new HashMap<>();
        Map<Integer, Integer> previousNode = new HashMap<>();
        Map<Integer, Pathway> previousPath = new HashMap<>();
        Set<Integer> finished = new HashSet<>();
        PriorityQueue<QueueEntry> queue = new PriorityQueue<>(
                Comparator.comparingDouble((QueueEntry e) -> e.cost).thenComparingInt(e -> e.locationId));

        cost.put(sourceId, 0.0);
        queue.add(new QueueEntry(sourceId, 0.0));
        int expanded = 0;

        while (!queue.isEmpty()) {
            QueueEntry current = queue.poll();
            int u = current.locationId;
            if (finished.contains(u)) {
                continue;
            }
            if (u == destinationId) {
                break;
            }
            finished.add(u);
            expanded++;

            for (Edge edge : graph.getNeighbors(u)) {
                int v = edge.getNeighborId();
                if (finished.contains(v)) {
                    continue;
                }
                double segment = prefs.segmentCost(edge.getPathway());
                if (Double.isInfinite(segment)) {
                    continue;                           // HARD RULE: this pathway may not be used
                }
                double newCost = cost.get(u) + segment;
                Double oldCost = cost.get(v);
                if (oldCost == null || newCost < oldCost) {
                    cost.put(v, newCost);
                    previousNode.put(v, u);
                    previousPath.put(v, edge.getPathway());
                    queue.add(new QueueEntry(v, newCost));
                }
            }
        }

        if (!previousNode.containsKey(destinationId)) {
            return RouteResult.notFound(NAME, metric, sourceId, destinationId, expanded,
                    explainFailure(graph, sourceId, destinationId, prefs));
        }

        // Rebuild the route backwards (same technique as Dijkstra)
        LinkedList<Integer> locationIds = new LinkedList<>();
        LinkedList<Pathway> pathways = new LinkedList<>();
        int at = destinationId;
        locationIds.addFirst(at);
        while (at != sourceId) {
            pathways.addFirst(previousPath.get(at));
            at = previousNode.get(at);
            locationIds.addFirst(at);
        }

        RouteResult route = RouteResult.found(NAME, metric, sourceId, destinationId,
                new ArrayList<>(locationIds), new ArrayList<>(pathways), expanded, "Route found.");
        return route.withContextualCost(cost.get(destinationId), "Route found.");
    }

    /**
     * Builds a helpful message for "no allowed route". It runs a plain search WITHOUT the rules
     * to find out whether the places are connected at all, and if so, which rules stand in the way.
     * (That plain route is used only to write the message. It is never returned as a route.)
     */
    private String explainFailure(CampusGraph graph, int sourceId, int destinationId, RoutingPreferences prefs) {
        RouteResult ignoringRules = new DijkstraAlgorithm().findRoute(graph, sourceId, destinationId, prefs.getMetric());
        if (!ignoringRules.isFound()) {
            return "No route exists between these two locations.";
        }
        RouteEvaluation evaluation = new RouteEvaluation(ignoringRules, prefs);
        if (evaluation.getViolations().isEmpty()) {
            return "No route is available under the current rules.";
        }
        return "No allowed route exists. The places are connected, but the shortest ordinary route "
                + String.join(" and ", evaluation.getViolations())
                + ", and no other route avoids every such pathway.";
    }
}
