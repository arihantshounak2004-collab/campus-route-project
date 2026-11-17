package model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * RouteResult: the answer returned by every routing algorithm.
 *
 * Either a route WAS found (found == true, with the list of locations and pathways)
 * or it was NOT found (found == false, and "message" explains why).
 * Algorithms never throw exceptions for "no route": they return a result that says so.
 *
 * Units: distance in metres, time in seconds.
 *
 * Note: a plain shortest-path algorithm does not look at crowds or closures, so its
 * route may use a BLOCKED path. The has...() methods let the user interface warn about that.
 */
public class RouteResult {

    private final String algorithm;          // e.g. "Dijkstra"
    private final CostMetric metric;
    private final boolean found;
    private final String message;
    private final int sourceId;
    private final int destinationId;
    private final List<Integer> locationIds;  // in walking order, from source to destination
    private final List<Pathway> pathways;     // pathways[i] joins locationIds[i] and locationIds[i+1]
    private final double totalDistance;
    private final int totalTime;
    private final double totalCost;           // sum of the metric that was minimised
    private final int nodesExpanded;          // how many locations the search examined (efficiency measure)
    private final double contextualCost;      // cost INCLUDING crowd/restricted penalties (NaN = not calculated)

    private RouteResult(String algorithm, CostMetric metric, boolean found, String message,
                        int sourceId, int destinationId, List<Integer> locationIds,
                        List<Pathway> pathways, int nodesExpanded, double contextualCost) {
        this.algorithm = algorithm;
        this.metric = metric;
        this.found = found;
        this.message = message;
        this.sourceId = sourceId;
        this.destinationId = destinationId;
        this.locationIds = Collections.unmodifiableList(new ArrayList<>(locationIds));
        this.pathways = Collections.unmodifiableList(new ArrayList<>(pathways));
        this.nodesExpanded = nodesExpanded;
        this.contextualCost = contextualCost;

        double dist = 0;
        int time = 0;
        double cost = 0;
        for (Pathway p : pathways) {
            dist += p.getDistance();
            time += p.getEstimatedTime();
            cost += metric.costOf(p);
        }
        this.totalDistance = Math.round(dist * 10.0) / 10.0;   // remove tiny floating-point noise
        this.totalTime = time;
        this.totalCost = Math.round(cost * 10.0) / 10.0;
    }

    /** Creates the result for a route that was found. Totals are calculated from the pathways. */
    public static RouteResult found(String algorithm, CostMetric metric, int sourceId, int destinationId,
                                    List<Integer> locationIds, List<Pathway> pathways,
                                    int nodesExpanded, String message) {
        return new RouteResult(algorithm, metric, true, message, sourceId, destinationId,
                locationIds, pathways, nodesExpanded, Double.NaN);
    }

    /** Creates the result for "no route". */
    public static RouteResult notFound(String algorithm, CostMetric metric, int sourceId, int destinationId,
                                       int nodesExpanded, String message) {
        return new RouteResult(algorithm, metric, false, message, sourceId, destinationId,
                new ArrayList<Integer>(), new ArrayList<Pathway>(), nodesExpanded, Double.NaN);
    }

    /**
     * Returns a copy of this (found) route that also records its CONTEXTUAL cost, i.e. the
     * cost including crowd and restricted-path penalties, plus a new message.
     */
    public RouteResult withContextualCost(double cost, String newMessage) {
        return new RouteResult(algorithm, metric, found, newMessage, sourceId, destinationId,
                locationIds, pathways, nodesExpanded, Math.round(cost * 10.0) / 10.0);
    }

    // ---------------- basic getters ----------------
    public String getAlgorithm()          { return algorithm; }
    public CostMetric getMetric()         { return metric; }
    public boolean isFound()              { return found; }
    public String getMessage()            { return message; }
    public int getSourceId()              { return sourceId; }
    public int getDestinationId()         { return destinationId; }
    public List<Integer> getLocationIds() { return locationIds; }
    public List<Pathway> getPathways()    { return pathways; }
    public double getTotalDistance()      { return totalDistance; }
    public int getTotalTime()             { return totalTime; }
    public double getTotalCost()          { return totalCost; }
    public int getNodesExpanded()         { return nodesExpanded; }

    /** Cost including penalties. Only meaningful if hasContextualCost() is true. */
    public double getContextualCost()     { return contextualCost; }
    public boolean hasContextualCost()    { return !Double.isNaN(contextualCost); }

    // ---------------- facts about the route ----------------

    /** Number of locations on the route including start and end (0 if no route). */
    public int getLocationCount() { return locationIds.size(); }

    /** Number of locations strictly between the start and the end. */
    public int getIntermediateCount() { return Math.max(0, locationIds.size() - 2); }

    /** How many pathway segments have this crowd level. */
    public int countCrowd(CrowdLevel level) {
        int n = 0;
        for (Pathway p : pathways) {
            if (p.getCrowdLevel() == level) n++;
        }
        return n;
    }

    /** How many pathway segments have this status. */
    public int countStatus(PathwayStatus status) {
        int n = 0;
        for (Pathway p : pathways) {
            if (p.getStatus() == status) n++;
        }
        return n;
    }

    public boolean usesBlockedPathway()    { return countStatus(PathwayStatus.BLOCKED) > 0; }
    public boolean usesRestrictedPathway() { return countStatus(PathwayStatus.RESTRICTED) > 0; }

    /** True if at least one segment is not accessible (for example has steps). */
    public boolean usesInaccessiblePathway() {
        for (Pathway p : pathways) {
            if (!p.isAccessible()) return true;
        }
        return false;
    }

    @Override
    public String toString() {
        if (!found) {
            return algorithm + ": NO ROUTE (" + message + ")";
        }
        return algorithm + ": " + locationIds + ", " + totalDistance + " m, " + totalTime + " s";
    }
}
