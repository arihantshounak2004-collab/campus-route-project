package algorithm;

import graph.CampusGraph;
import graph.Edge;
import model.CostMetric;
import model.Pathway;
import model.RouteResult;

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
 * DijkstraAlgorithm - finds the route with the smallest total cost (distance or time).
 *
 * THE IDEA (in plain words)
 *   Imagine pouring water at the starting location. It spreads along the pathways, and
 *   the first time it touches a location, it got there by the cheapest possible way.
 *   Dijkstra simulates this: it always processes the not-yet-finished location that is
 *   CLOSEST to the start.
 *
 * DATA KEPT BY THE ALGORITHM
 *   distance      : Map  location -> cheapest cost found so far from the start
 *   previousNode  : Map  location -> the location we came from on that cheapest way
 *   previousPath  : Map  location -> the pathway we used to arrive
 *   queue         : PriorityQueue of (location, cost). poll() always gives the SMALLEST cost.
 *   finished      : Set of locations whose cheapest cost is final
 *
 * STEPS
 *   1. distance[start] = 0, put start in the queue.
 *   2. Repeat: take the cheapest location "u" from the queue.
 *        - If u was already finished, ignore this old queue entry.
 *        - If u is the destination, STOP: its cost is final.
 *        - Mark u finished. For every neighbour "v" of u:
 *              newCost = distance[u] + cost(u -> v)
 *              if newCost < distance[v]  ->  we found a cheaper way to v:
 *                    update distance[v], previousNode[v] = u, put v in the queue.
 *   3. Rebuild the route by walking BACKWARDS from the destination using previousNode.
 *
 * WHY IT IS CORRECT: all costs are positive, so once a location is taken from the queue
 *   no later path can reach it more cheaply.
 *
 * NOTE ON "STALE" ENTRIES: Java's PriorityQueue cannot lower the cost of an entry that is
 *   already inside. So we simply add a NEW entry with the lower cost, and skip old entries
 *   when they come out (step 2, first bullet). This is simple and fast enough here.
 *
 * This class looks only at the chosen cost (distance or time). It does NOT look at crowd,
 * closures or accessibility - that is the job of ContextAwareRouter (Phase 6).
 */
public class DijkstraAlgorithm {

    private static final String NAME = "Dijkstra";

    /** One item in the priority queue: a location and the cost to reach it. */
    private static class QueueEntry {
        final int locationId;
        final double cost;

        QueueEntry(int locationId, double cost) {
            this.locationId = locationId;
            this.cost = cost;
        }
    }

    public RouteResult findRoute(CampusGraph graph, int sourceId, int destinationId, CostMetric metric) {

        // Handle unknown locations and "already there" first.
        RouteResult early = RouteChecks.checkInput(graph, sourceId, destinationId, NAME, metric);
        if (early != null) {
            return early;
        }

        Map<Integer, Double> distance = new HashMap<>();
        Map<Integer, Integer> previousNode = new HashMap<>();
        Map<Integer, Pathway> previousPath = new HashMap<>();
        Set<Integer> finished = new HashSet<>();

        // Smallest cost first; if two costs are equal, smaller id first (keeps results repeatable).
        PriorityQueue<QueueEntry> queue = new PriorityQueue<>(
                Comparator.comparingDouble((QueueEntry e) -> e.cost).thenComparingInt(e -> e.locationId));

        // Step 1
        distance.put(sourceId, 0.0);
        queue.add(new QueueEntry(sourceId, 0.0));
        int expanded = 0;

        // Step 2
        while (!queue.isEmpty()) {
            QueueEntry current = queue.poll();
            int u = current.locationId;

            if (finished.contains(u)) {
                continue;                      // stale entry: u was already finalised with a cheaper cost
            }
            if (u == destinationId) {
                break;                         // the destination's cost is final
            }
            finished.add(u);
            expanded++;

            for (Edge edge : graph.getNeighbors(u)) {
                int v = edge.getNeighborId();
                if (finished.contains(v)) {
                    continue;
                }
                double newCost = distance.get(u) + metric.costOf(edge.getPathway());
                Double oldCost = distance.get(v);
                if (oldCost == null || newCost < oldCost) {    // found a cheaper way to v
                    distance.put(v, newCost);
                    previousNode.put(v, u);
                    previousPath.put(v, edge.getPathway());
                    queue.add(new QueueEntry(v, newCost));
                }
            }
        }

        // Destination never reached = no route (the graph is split into separate parts).
        if (!previousNode.containsKey(destinationId)) {
            return RouteResult.notFound(NAME, metric, sourceId, destinationId, expanded,
                    "No route exists between these two locations.");
        }

        // Step 3: walk backwards from the destination to the start, then the lists are in walking order.
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
}
