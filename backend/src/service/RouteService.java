package service;

import algorithm.AStarAlgorithm;
import algorithm.ContextAwareRouter;
import algorithm.DijkstraAlgorithm;
import algorithm.RouteEvaluation;
import graph.CampusGraph;
import model.RouteResult;
import model.RoutingPreferences;

import java.sql.SQLException;

/**
 * RouteService - the business logic between the HTTP handlers and the algorithms.
 *
 * For every request it:
 *   1. loads a FRESH graph from MySQL (so admin changes made a second ago are used;
 *      the campus is small, so this is fast, and each request has its own graph, which is thread-safe);
 *   2. checks that the two locations exist (NotFoundException -> HTTP 404);
 *   3. builds the routing rules (RoutingPreferences). The server alone decides whether restricted
 *      pathways are permitted: the caller passes "permitRestricted", and it is true only for a
 *      logged-in administrator (Phase 10).
 *   4. runs the algorithm(s).
 */
public class RouteService {

    /** One route plus the graph it came from (the graph is needed to look up location names) and its rule check. */
    public static class SingleRoute {
        public final CampusGraph graph;
        public final RouteResult route;
        public final RouteEvaluation evaluation;
        public final RoutingPreferences preferences;

        SingleRoute(CampusGraph graph, RouteResult route, RouteEvaluation evaluation, RoutingPreferences preferences) {
            this.graph = graph;
            this.route = route;
            this.evaluation = evaluation;
            this.preferences = preferences;
        }
    }

    /** The three-way comparison plus its graph. */
    public static class Comparison {
        public final CampusGraph graph;
        public final RouteComparison comparison;

        Comparison(CampusGraph graph, RouteComparison comparison) {
            this.graph = graph;
            this.comparison = comparison;
        }
    }

    /** Plain Dijkstra shortest route. It ignores crowds and closures; the evaluation flags any rule it breaks. */
    public SingleRoute shortest(RoutingRequest request, boolean permitRestricted) throws SQLException {
        CampusGraph graph = loadAndCheck(request);
        RoutingPreferences prefs = buildPreferences(request, permitRestricted);
        RouteResult route = new DijkstraAlgorithm().findRoute(graph, request.sourceId, request.destinationId, prefs.getMetric());
        return new SingleRoute(graph, route, new RouteEvaluation(route, prefs), prefs);
    }

    /** A* route (same total cost as Dijkstra, found with the help of map coordinates). */
    public SingleRoute astar(RoutingRequest request, boolean permitRestricted) throws SQLException {
        CampusGraph graph = loadAndCheck(request);
        RoutingPreferences prefs = buildPreferences(request, permitRestricted);
        RouteResult route = new AStarAlgorithm().findRoute(graph, request.sourceId, request.destinationId, prefs.getMetric());
        return new SingleRoute(graph, route, new RouteEvaluation(route, prefs), prefs);
    }

    /** The context-aware route: follows the hard rules and avoids crowds. */
    public SingleRoute contextAware(RoutingRequest request, boolean permitRestricted) throws SQLException {
        CampusGraph graph = loadAndCheck(request);
        RoutingPreferences prefs = buildPreferences(request, permitRestricted);
        RouteResult route = new ContextAwareRouter().findRoute(graph, request.sourceId, request.destinationId, prefs);
        return new SingleRoute(graph, route, new RouteEvaluation(route, prefs), prefs);
    }

    /** All three routes side by side with an explanation. */
    public Comparison compare(RoutingRequest request, boolean permitRestricted) throws SQLException {
        CampusGraph graph = loadAndCheck(request);
        RoutingPreferences prefs = buildPreferences(request, permitRestricted);
        return new Comparison(graph, RouteComparison.compare(graph, request.sourceId, request.destinationId, prefs));
    }

    // ------------------------------------------------------------------

    private CampusGraph loadAndCheck(RoutingRequest request) throws SQLException {
        CampusGraph graph = CampusGraph.loadFromDatabase();
        if (!graph.contains(request.sourceId)) {
            throw new NotFoundException("Starting location " + request.sourceId + " does not exist or is inactive");
        }
        if (!graph.contains(request.destinationId)) {
            throw new NotFoundException("Destination " + request.destinationId + " does not exist or is inactive");
        }
        return graph;
    }

    /** Turns the client's limited request into full routing rules. Costs and penalties come from the server defaults. */
    private RoutingPreferences buildPreferences(RoutingRequest request, boolean permitRestricted) {
        return RoutingPreferences.defaults()
                .setMetric(request.metric)
                .setRequireAccessible(request.requireAccessible)
                .setCrowdWeight(request.crowdWeight)
                .setAllowRestricted(permitRestricted);      // never copied from the client's request
    }
}
