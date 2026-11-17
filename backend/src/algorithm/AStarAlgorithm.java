package algorithm;

import graph.CampusGraph;
import graph.Edge;
import model.CostMetric;
import model.Location;
import model.Pathway;
import model.RouteResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * AStarAlgorithm - like Dijkstra, but it uses the map coordinates to head TOWARDS the destination.
 *
 * THE IDEA
 *   Dijkstra spreads in all directions equally. A* adds a "guess" of how much cost is still
 *   left to reach the destination, and processes the most promising location first:
 *
 *        f(n) = g(n) + h(n)
 *
 *        g(n) = the REAL cost from the start to n   (the "g-score", exactly what Dijkstra tracks)
 *        h(n) = the GUESS of the cost from n to the destination (the "heuristic")
 *        f(n) = the estimated total cost of a route that goes through n (the "f-score")
 *
 *   The priority queue always gives the location with the smallest f-score.
 *
 * THE HEURISTIC  h(n) = scale x (straight-line distance on the map between n and the destination)
 *   For A* to always return the BEST route, h must never OVERESTIMATE the real remaining cost.
 *   A straight line is the shortest way between two points, so if "scale" is not larger than the
 *   real cost per map unit on any pathway, h can never be too big.
 *
 *   We do not guess this scale. heuristicScale() measures it from the pathways themselves:
 *        scale = the SMALLEST (pathway cost / straight-line length of that pathway) in the graph.
 *   This works for both metrics (metres or seconds), and stays correct even if an administrator
 *   later adds a pathway with unusual values. (If the scale were too large, A* could return a
 *   route that is not the best.)
 *
 * STEPS
 *   1. g[start] = 0, f[start] = h(start), put start in the open queue.
 *   2. Repeat: take the location with the smallest f from the queue.
 *        - If it was already closed, skip it.
 *        - If it is the destination, STOP and rebuild the route.
 *        - Close it. For each neighbour: tentative g = g[current] + cost(edge).
 *          If that is better than the neighbour's known g, record it, set
 *          f = g + h(neighbour), and add the neighbour to the queue.
 *   3. Rebuild the route backwards using the "came from" maps.
 *
 * Like Dijkstra, this class ignores crowd, closures and accessibility (see ContextAwareRouter).
 * Because the heuristic never overestimates and is consistent, A* finds a route with the SAME
 * total cost as Dijkstra, usually examining fewer locations. (If two routes have exactly equal
 * cost, the two algorithms may pick different ones.)
 */
public class AStarAlgorithm {

    private static final String NAME = "A*";

    /** One item in the open queue. */
    private static class QueueEntry {
        final int locationId;
        final double fScore;

        QueueEntry(int locationId, double fScore) {
            this.locationId = locationId;
            this.fScore = fScore;
        }
    }

    public RouteResult findRoute(CampusGraph graph, int sourceId, int destinationId, CostMetric metric) {

        RouteResult early = RouteChecks.checkInput(graph, sourceId, destinationId, NAME, metric);
        if (early != null) {
            return early;
        }

        double scale = heuristicScale(graph, metric);

        Map<Integer, Double> gScore = new HashMap<>();
        Map<Integer, Double> fScore = new HashMap<>();
        Map<Integer, Integer> previousNode = new HashMap<>();
        Map<Integer, Pathway> previousPath = new HashMap<>();
        Set<Integer> closed = new HashSet<>();

        PriorityQueue<QueueEntry> open = new PriorityQueue<>(
                Comparator.comparingDouble((QueueEntry e) -> e.fScore).thenComparingInt(e -> e.locationId));

        // Step 1
        gScore.put(sourceId, 0.0);
        double startF = heuristic(graph, sourceId, destinationId, scale);
        fScore.put(sourceId, startF);
        open.add(new QueueEntry(sourceId, startF));
        int expanded = 0;
        boolean reached = false;

        // Step 2
        while (!open.isEmpty()) {
            QueueEntry current = open.poll();
            int u = current.locationId;

            if (closed.contains(u)) {
                continue;                       // stale entry
            }
            if (u == destinationId) {
                reached = true;
                break;
            }
            closed.add(u);
            expanded++;

            for (Edge edge : graph.getNeighbors(u)) {
                int v = edge.getNeighborId();
                if (closed.contains(v)) {
                    continue;
                }
                double tentativeG = gScore.get(u) + metric.costOf(edge.getPathway());
                Double knownG = gScore.get(v);
                if (knownG == null || tentativeG < knownG) {      // a better way to v
                    gScore.put(v, tentativeG);
                    double f = tentativeG + heuristic(graph, v, destinationId, scale);
                    fScore.put(v, f);
                    previousNode.put(v, u);
                    previousPath.put(v, edge.getPathway());
                    open.add(new QueueEntry(v, f));
                }
            }
        }

        if (!reached) {
            return RouteResult.notFound(NAME, metric, sourceId, destinationId, expanded,
                    "No route exists between these two locations.");
        }

        // Step 3: rebuild the route backwards
        LinkedList<Integer> locationIds = new LinkedList<>();
        LinkedList<Pathway> pathways = new LinkedList<>();
        int at = destinationId;
        locationIds.addFirst(at);
        while (at != sourceId) {
            pathways.addFirst(previousPath.get(at));
            at = previousNode.get(at);
            locationIds.addFirst(at);
        }

        return RouteResult.found(NAME, metric, sourceId, destinationId,
                new ArrayList<>(locationIds), new ArrayList<>(pathways), expanded, "Route found.");
    }

    // ------------------------------------------------------------------
    // The heuristic (public + static so the tests can check that it is safe)
    // ------------------------------------------------------------------

    /**
     * The scale that converts straight-line map units into the chosen cost.
     * = the smallest (cost / straight-line length) over all pathways of the graph.
     * Using ALL pathways (even blocked ones) can only make the scale smaller, which is safe.
     * Returns 0 if the graph has no usable pathway (then A* behaves exactly like Dijkstra).
     */
    public static double heuristicScale(CampusGraph graph, CostMetric metric) {
        double smallest = Double.MAX_VALUE;
        for (Pathway p : graph.getPathways()) {
            Location a = graph.getLocation(p.getSourceId());
            Location b = graph.getLocation(p.getDestinationId());
            double straightLine = straightLineDistance(a, b);
            if (straightLine > 0) {                       // two places drawn at the same point say nothing
                smallest = Math.min(smallest, metric.costOf(p) / straightLine);
            }
        }
        return smallest == Double.MAX_VALUE ? 0.0 : smallest;
    }

    /** The guess h(from -> to): scale x straight-line distance between the two map positions. */
    public static double heuristic(CampusGraph graph, int fromId, int toId, double scale) {
        return scale * straightLineDistance(graph.getLocation(fromId), graph.getLocation(toId));
    }

    /** Straight-line (Euclidean) distance between two locations on the map canvas. */
    private static double straightLineDistance(Location a, Location b) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        return Math.sqrt(dx * dx + dy * dy);
    }
}
