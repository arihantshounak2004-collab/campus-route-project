package server;

import com.sun.net.httpserver.HttpExchange;
import dao.FacilityDAO;
import dao.LocationDAO;
import graph.CampusGraph;
import model.AvailabilityStatus;
import model.CrowdLevel;
import model.Facility;
import model.FacilityType;
import model.Location;
import model.Pathway;
import model.PathwayStatus;
import util.ValidationException;
import util.ValidationUtil;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CampusDataHandler - the read-only (GET) endpoints that describe the campus:
 *
 *   GET /api/health       is the server (and the database) working?
 *   GET /api/locations    all ACTIVE locations
 *   GET /api/pathways     all pathways between active locations (including blocked ones, so the map can draw them)
 *   GET /api/facilities   all facilities; optional filters ?type=RESTROOM  ?locationId=8
 *   GET /api/summary      counts for the dashboard
 */
class CampusDataHandler extends BaseHandler {

    CampusDataHandler(String corsOrigin) { super(corsOrigin); }

    @Override
    protected void route(HttpExchange exchange) throws Exception {
        String path = exchange.getRequestURI().getPath();
        switch (path) {
            case "/api/health":     requireMethod(exchange, "GET", "HEAD"); health(exchange);     break;
            case "/api/locations":  requireMethod(exchange, "GET", "HEAD"); locations(exchange);  break;
            case "/api/pathways":   requireMethod(exchange, "GET", "HEAD"); pathways(exchange);   break;
            case "/api/facilities": requireMethod(exchange, "GET", "HEAD"); facilities(exchange); break;
            case "/api/summary":    requireMethod(exchange, "GET", "HEAD"); summary(exchange);    break;
            default: throw new ApiException(404, "No such API endpoint");
        }
    }

    private void health(HttpExchange exchange) throws Exception {
        boolean databaseOk;
        try (Connection con = database.DatabaseConnection.getConnection()) {
            databaseOk = con.isValid(3);
        } catch (SQLException e) {
            System.err.println("[health] database check failed: " + e.getMessage());
            databaseOk = false;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", databaseOk ? "ok" : "degraded");
        body.put("database", databaseOk);
        sendJson(exchange, databaseOk ? 200 : 503, body);
    }

    private void locations(HttpExchange exchange) throws Exception {
        List<Object> list = new ArrayList<>();
        for (Location l : new LocationDAO().findAll()) {
            if (l.isActive()) {
                list.add(ResponseMapper.location(l));
            }
        }
        sendJson(exchange, 200, list);
    }

    private void pathways(HttpExchange exchange) throws Exception {
        CampusGraph graph = CampusGraph.loadFromDatabase();     // only valid pathways between active locations
        List<Object> list = new ArrayList<>();
        for (Pathway p : graph.getPathways()) {
            list.add(ResponseMapper.pathway(p, graph));
        }
        sendJson(exchange, 200, list);
    }

    private void facilities(HttpExchange exchange) throws Exception {
        Map<String, String> query = queryParams(exchange);
        FacilityType typeFilter = null;
        Integer locationFilter = null;
        if (query.containsKey("type")) {
            typeFilter = ValidationUtil.parseEnum(query.get("type"), FacilityType.class, "type");
        }
        if (query.containsKey("locationId")) {
            try {
                locationFilter = Integer.valueOf(query.get("locationId").trim());
            } catch (NumberFormatException e) {
                throw new ValidationException("Parameter 'locationId' must be a whole number");
            }
        }

        Map<Integer, Location> locationById = new HashMap<>();
        for (Location l : new LocationDAO().findAll()) {
            locationById.put(l.getId(), l);
        }
        List<Object> list = new ArrayList<>();
        for (Facility f : new FacilityDAO().findAll()) {
            Location where = locationById.get(f.getLocationId());
            if (where == null || !where.isActive()) continue;                     // facility of a deactivated location
            if (typeFilter != null && f.getType() != typeFilter) continue;
            if (locationFilter != null && f.getLocationId() != locationFilter) continue;
            list.add(ResponseMapper.facility(f, where));
        }
        sendJson(exchange, 200, list);
    }

    private void summary(HttpExchange exchange) throws Exception {
        CampusGraph graph = CampusGraph.loadFromDatabase();
        int open = 0, restricted = 0, blocked = 0, notAccessible = 0;
        Map<String, Object> crowd = new LinkedHashMap<>();
        int[] crowdCounts = new int[CrowdLevel.values().length];
        for (Pathway p : graph.getPathways()) {
            if (p.getStatus() == PathwayStatus.OPEN) open++;
            else if (p.getStatus() == PathwayStatus.RESTRICTED) restricted++;
            else blocked++;
            if (!p.isAccessible()) notAccessible++;
            crowdCounts[p.getCrowdLevel().ordinal()]++;
        }
        for (CrowdLevel level : CrowdLevel.values()) {
            crowd.put(level.name(), crowdCounts[level.ordinal()]);
        }

        int available = 0, facilityTotal = 0;
        for (Facility f : new FacilityDAO().findAll()) {
            Location where = graph.getLocation(f.getLocationId());
            if (where == null) continue;
            facilityTotal++;
            if (f.getAvailability() == AvailabilityStatus.AVAILABLE) available++;
        }

        Map<String, Object> pathways = new LinkedHashMap<>();
        pathways.put("total", graph.getPathwayCount());
        pathways.put("open", open);
        pathways.put("restricted", restricted);
        pathways.put("blocked", blocked);
        pathways.put("notAccessible", notAccessible);
        pathways.put("available", open + restricted);            // not blocked

        Map<String, Object> facilities = new LinkedHashMap<>();
        facilities.put("total", facilityTotal);
        facilities.put("available", available);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("locations", graph.getLocationCount());
        body.put("pathways", pathways);
        body.put("crowd", crowd);
        body.put("facilities", facilities);
        sendJson(exchange, 200, body);
    }
}
