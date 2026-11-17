package server;

import com.sun.net.httpserver.HttpExchange;
import model.Facility;
import service.RecommendationService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RecommendationHandler - GET /api/recommendations
 *
 *   ?destinationId=8                  required: the place the user is going to
 *   &sourceId=1                       optional: lets the answer mark facilities that are ON the route
 *   &accessible=true                  optional: step-free walking only (default false)
 *
 * The recommendations come from the RULES in RecommendationService (not from machine learning).
 */
class RecommendationHandler extends BaseHandler {

    RecommendationHandler(String corsOrigin) { super(corsOrigin); }

    @Override
    protected void route(HttpExchange exchange) throws Exception {
        if (!exchange.getRequestURI().getPath().equals("/api/recommendations")) {
            throw new ApiException(404, "No such API endpoint");
        }
        requireMethod(exchange, "GET", "HEAD");
        Map<String, String> q = queryParams(exchange);

        int destinationId = requireId(q, "destinationId");
        Integer sourceId = q.containsKey("sourceId") ? Integer.valueOf(requireId(q, "sourceId")) : null;
        boolean accessible = false;
        if (q.containsKey("accessible")) {
            String v = q.get("accessible").trim();
            if (!v.equals("true") && !v.equals("false")) {
                throw new util.ValidationException("Parameter 'accessible' must be true or false");
            }
            accessible = v.equals("true");
        }

        RecommendationService.Result result = new RecommendationService().recommendFromDatabase(destinationId, sourceId, accessible);

        List<Object> items = new ArrayList<>();
        for (RecommendationService.Recommendation r : result.recommendations) {
            Facility f = r.facility;
            Map<String, Object> m = ResponseMapper.facility(f, r.location);
            m.put("distanceMetres", Math.round(r.distanceMetres * 10.0) / 10.0);
            m.put("atDestination", r.atDestination);
            m.put("onRoute", r.onRoute);
            m.put("score", Math.round(r.score * 10.0) / 10.0);
            m.put("explanation", r.explanation);
            items.add(m);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("destination", ResponseMapper.locationBrief(result.destination));
        body.put("method", "rule-based (not machine learning)");
        body.put("maxDistanceMetres", RecommendationService.MAX_DISTANCE_METRES);
        body.put("recommendations", items);
        body.put("notices", new ArrayList<Object>(result.notices));
        sendJson(exchange, 200, body);
    }

    private static int requireId(Map<String, String> q, String name) {
        String text = q.get(name);
        if (text == null) {
            throw new util.ValidationException("Parameter '" + name + "' is required");
        }
        try {
            int id = Integer.parseInt(text.trim());
            if (id < 1 || id > 1_000_000) throw new NumberFormatException();
            return id;
        } catch (NumberFormatException e) {
            throw new util.ValidationException("Parameter '" + name + "' must be a whole number between 1 and 1000000");
        }
    }
}
