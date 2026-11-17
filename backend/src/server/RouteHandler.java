package server;

import com.sun.net.httpserver.HttpExchange;
import graph.CampusGraph;
import model.CostMetric;
import model.Location;
import model.RoutingPreferences;
import service.RouteComparison;
import service.RouteService;
import service.RoutingRequest;
import util.ValidationUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RouteHandler - the route calculation endpoints (all POST with a JSON body):
 *
 *   POST /api/routes/shortest        Dijkstra
 *   POST /api/routes/astar           A*
 *   POST /api/routes/context-aware   rule-following, crowd-aware route
 *   POST /api/routes/compare         all three side by side + explanation
 *
 * REQUEST BODY (JSON):
 *   { "sourceId": 1, "destinationId": 8,
 *     "metric": "DISTANCE" | "TIME"          (optional, default DISTANCE)
 *     "requireAccessible": true | false      (optional, default false)
 *     "crowdWeight": 0 ... 10                (optional, default 1)  }
 *
 * The client can NOT choose costs, penalties or whether restricted pathways are permitted.
 * If a client sends "allowRestricted", it is ignored and the answer says so in "warnings".
 */
class RouteHandler extends BaseHandler {

    private final RouteService service = new RouteService();

    RouteHandler(String corsOrigin) { super(corsOrigin); }

    @Override
    protected void route(HttpExchange exchange) throws Exception {
        String path = exchange.getRequestURI().getPath();
        if (!path.equals("/api/routes/shortest") && !path.equals("/api/routes/astar")
                && !path.equals("/api/routes/context-aware") && !path.equals("/api/routes/compare")) {
            throw new ApiException(404, "No such API endpoint");
        }
        requireMethod(exchange, "POST");

        RoutingRequest request = parseRequest(readJsonBody(exchange));
        boolean permitRestricted = AdminAuth.isAdmin(exchange);       // decided by the SERVER, never by the request

        List<Object> warnings = new ArrayList<>();
        if (request.clientAskedForRestricted && !permitRestricted) {
            warnings.add("The field 'allowRestricted' was ignored: only a logged-in administrator may use restricted pathways.");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        switch (path) {
            case "/api/routes/shortest": {
                RouteService.SingleRoute r = service.shortest(request, permitRestricted);
                fillSingle(body, request, r, permitRestricted);
                break;
            }
            case "/api/routes/astar": {
                RouteService.SingleRoute r = service.astar(request, permitRestricted);
                fillSingle(body, request, r, permitRestricted);
                break;
            }
            case "/api/routes/context-aware": {
                RouteService.SingleRoute r = service.contextAware(request, permitRestricted);
                fillSingle(body, request, r, permitRestricted);
                break;
            }
            default: {   // /api/routes/compare
                RouteService.Comparison c = service.compare(request, permitRestricted);
                fillComparison(body, request, c, permitRestricted);
            }
        }
        body.put("warnings", warnings);
        sendJson(exchange, 200, body);
    }

    private RoutingRequest parseRequest(Map<String, Object> body) {
        int source = ValidationUtil.requireInt(body, "sourceId", 1, 1_000_000);
        int destination = ValidationUtil.requireInt(body, "destinationId", 1, 1_000_000);
        CostMetric metric = ValidationUtil.optionalEnum(body, "metric", CostMetric.class, CostMetric.DISTANCE);
        boolean accessible = ValidationUtil.optionalBoolean(body, "requireAccessible", false);
        double weight = ValidationUtil.optionalDouble(body, "crowdWeight", 1.0, 0.0, 10.0);
        boolean askedRestricted = ValidationUtil.optionalBoolean(body, "allowRestricted", false);
        return new RoutingRequest(source, destination, metric, accessible, weight, askedRestricted);
    }

    private void fillSingle(Map<String, Object> body, RoutingRequest request, RouteService.SingleRoute r, boolean permitRestricted) {
        addEndpoints(body, r.graph, request);
        body.put("preferences", ResponseMapper.preferences(r.preferences, permitRestricted));
        body.put("route", ResponseMapper.route(r.graph, r.route, r.evaluation));
    }

    private void fillComparison(Map<String, Object> body, RoutingRequest request, RouteService.Comparison c, boolean permitRestricted) {
        RouteComparison cmp = c.comparison;
        RoutingPreferences prefs = cmp.getPreferences();
        addEndpoints(body, c.graph, request);
        body.put("preferences", ResponseMapper.preferences(prefs, permitRestricted));

        Map<String, Object> routes = new LinkedHashMap<>();
        routes.put("shortest", ResponseMapper.route(c.graph, cmp.getShortest(), cmp.getShortestEvaluation()));
        routes.put("astar", ResponseMapper.route(c.graph, cmp.getAStar(), cmp.getAStarEvaluation()));
        routes.put("contextAware", ResponseMapper.route(c.graph, cmp.getContextAware(), cmp.getContextEvaluation()));
        body.put("routes", routes);

        body.put("recommended", cmp.getRecommended() == null ? null : "contextAware");
        body.put("routesIdentical", cmp.contextRouteSameAsShortest());
        body.put("explanation", new ArrayList<Object>(cmp.getExplanation()));
    }

    private void addEndpoints(Map<String, Object> body, CampusGraph graph, RoutingRequest request) {
        Location from = graph.getLocation(request.sourceId);
        Location to = graph.getLocation(request.destinationId);
        body.put("source", ResponseMapper.locationBrief(from));
        body.put("destination", ResponseMapper.locationBrief(to));
    }
}
