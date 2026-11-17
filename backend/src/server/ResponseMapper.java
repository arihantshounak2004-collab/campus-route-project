package server;

import algorithm.RouteEvaluation;
import graph.CampusGraph;
import model.CrowdLevel;
import model.Facility;
import model.Location;
import model.Pathway;
import model.PathwayStatus;
import model.RouteResult;
import model.RoutingPreferences;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ResponseMapper - converts Java objects into plain Maps and Lists, which JsonUtil can write as JSON.
 * It decides exactly which fields the outside world may see. Password hashes, database settings and
 * other internal data are never added here.
 */
final class ResponseMapper {
    private ResponseMapper() { }

    static Map<String, Object> location(Location l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", l.getId());
        m.put("name", l.getName());
        m.put("type", l.getType());
        m.put("description", l.getDescription());
        m.put("x", l.getX());
        m.put("y", l.getY());
        m.put("active", l.isActive());
        return m;
    }

    static Map<String, Object> locationBrief(Location l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", l.getId());
        m.put("name", l.getName());
        m.put("type", l.getType());
        m.put("x", l.getX());
        m.put("y", l.getY());
        return m;
    }

    static Map<String, Object> pathway(Pathway p, CampusGraph graph) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("sourceId", p.getSourceId());
        m.put("sourceName", graph.getLocation(p.getSourceId()).getName());
        m.put("destinationId", p.getDestinationId());
        m.put("destinationName", graph.getLocation(p.getDestinationId()).getName());
        m.put("distance", p.getDistance());
        m.put("estimatedTime", p.getEstimatedTime());
        m.put("crowdLevel", p.getCrowdLevel());
        m.put("status", p.getStatus());
        m.put("accessible", p.isAccessible());
        return m;
    }

    static Map<String, Object> facility(Facility f, Location where) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", f.getId());
        m.put("name", f.getName());
        m.put("type", f.getType());
        m.put("locationId", f.getLocationId());
        m.put("locationName", where == null ? null : where.getName());
        m.put("description", f.getDescription());
        m.put("availability", f.getAvailability());
        return m;
    }

    static Map<String, Object> preferences(RoutingPreferences p, boolean restrictedPermitted) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("metric", p.getMetric());
        m.put("requireAccessible", p.isRequireAccessible());
        m.put("crowdWeight", p.getCrowdWeight());
        Map<String, Object> penalties = new LinkedHashMap<>();
        penalties.put("LOW", p.crowdPenalty(CrowdLevel.LOW));
        penalties.put("MEDIUM", p.crowdPenalty(CrowdLevel.MEDIUM));
        penalties.put("HIGH", p.crowdPenalty(CrowdLevel.HIGH));
        m.put("crowdPenalties", penalties);
        m.put("penaltyUnit", p.getMetric() == model.CostMetric.TIME ? "second-equivalents" : "metre-equivalents");
        m.put("restrictedPathsPermitted", restrictedPermitted);
        return m;
    }

    /** One route with its locations, segments, totals and rule check. */
    static Map<String, Object> route(CampusGraph graph, RouteResult r, RouteEvaluation e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("algorithm", r.getAlgorithm());
        m.put("found", r.isFound());
        m.put("message", r.getMessage());
        m.put("metric", r.getMetric());

        List<Object> locations = new ArrayList<>();
        for (int id : r.getLocationIds()) {
            locations.add(locationBrief(graph.getLocation(id)));
        }
        m.put("locations", locations);

        List<Object> segments = new ArrayList<>();
        List<Integer> ids = r.getLocationIds();
        for (int i = 0; i < r.getPathways().size(); i++) {
            Pathway p = r.getPathways().get(i);
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("pathwayId", p.getId());
            s.put("fromId", ids.get(i));
            s.put("fromName", graph.getLocation(ids.get(i)).getName());
            s.put("toId", ids.get(i + 1));
            s.put("toName", graph.getLocation(ids.get(i + 1)).getName());
            s.put("distance", p.getDistance());
            s.put("time", p.getEstimatedTime());
            s.put("crowdLevel", p.getCrowdLevel());
            s.put("status", p.getStatus());
            s.put("accessible", p.isAccessible());
            segments.add(s);
        }
        m.put("segments", segments);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalDistance", r.getTotalDistance());
        summary.put("totalTimeSeconds", r.getTotalTime());
        summary.put("walkingMinutes", Math.round(r.getTotalTime() / 60.0 * 10.0) / 10.0);
        summary.put("locationCount", r.getLocationCount());
        summary.put("intermediateLocations", r.getIntermediateCount());
        summary.put("nodesExpanded", r.getNodesExpanded());
        Map<String, Object> crowd = new LinkedHashMap<>();
        for (CrowdLevel level : CrowdLevel.values()) crowd.put(level.name(), r.countCrowd(level));
        summary.put("crowd", crowd);
        Map<String, Object> status = new LinkedHashMap<>();
        for (PathwayStatus s : PathwayStatus.values()) status.put(s.name(), r.countStatus(s));
        summary.put("status", status);
        summary.put("usesInaccessiblePathway", r.usesInaccessiblePathway());
        m.put("summary", summary);

        Map<String, Object> evaluation = new LinkedHashMap<>();
        evaluation.put("recommendable", e.isRecommendable());
        evaluation.put("violations", new ArrayList<Object>(e.getViolations()));
        evaluation.put("contextualCost", e.hasContextualCost() ? (Object) e.getContextualCost() : null);
        m.put("evaluation", evaluation);
        return m;
    }
}
