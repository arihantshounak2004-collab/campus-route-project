package algorithm;

import graph.CampusGraph;
import model.CostMetric;
import model.Pathway;
import model.RouteResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Input checks shared by all routing algorithms, so every algorithm treats
 * bad input and the "already there" case in exactly the same way.
 */
final class RouteChecks {

    private RouteChecks() { }

    /**
     * Returns a ready-made RouteResult if the request needs no search
     * (unknown location, or source == destination). Returns null if a search is needed.
     */
    static RouteResult checkInput(CampusGraph graph, int sourceId, int destinationId,
                                  String algorithm, CostMetric metric) {
        if (!graph.contains(sourceId)) {
            return RouteResult.notFound(algorithm, metric, sourceId, destinationId, 0,
                    "The starting location (id " + sourceId + ") does not exist or is inactive.");
        }
        if (!graph.contains(destinationId)) {
            return RouteResult.notFound(algorithm, metric, sourceId, destinationId, 0,
                    "The destination (id " + destinationId + ") does not exist or is inactive.");
        }
        if (sourceId == destinationId) {
            List<Integer> onlyHere = Collections.singletonList(sourceId);
            return RouteResult.found(algorithm, metric, sourceId, destinationId, onlyHere,
                    new ArrayList<Pathway>(), 0, "You are already at your destination.");
        }
        return null;
    }
}
