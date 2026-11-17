import server.ApiServer;
import util.JsonUtil;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * ApiServerTest  (Phase 7)
 * ------------------------
 * Starts the REAL web server on a free port and talks to it with REAL HTTP requests.
 * Needs MySQL running with the sample database.
 *
 * Run from the PROJECT ROOT:   java -cp "out;lib\*" ApiServerTest
 */
public class ApiServerTest {

    private static int passed = 0;
    private static int failed = 0;
    private static String base;       // e.g. http://127.0.0.1:53211
    private static int port;

    private static void check(String name, boolean ok, String detail) {
        if (ok) { passed++; } else { failed++; }
        System.out.println("  " + (ok ? "PASS" : "FAIL") + "  " + name + (detail.isEmpty() ? "" : "  -> " + detail));
    }

    // ------------------------------------------------------------------
    // A tiny HTTP client
    // ------------------------------------------------------------------
    private static class Response {
        int status;
        String body = "";
        Map<String, List<String>> headers;

        String header(String name) {
            if (headers == null) return null;
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) return e.getValue().get(0);
            }
            return null;
        }
        Object json() { return JsonUtil.parse(body); }
        @SuppressWarnings("unchecked") Map<String, Object> obj() { return (Map<String, Object>) json(); }
        @SuppressWarnings("unchecked") List<Object> list() { return (List<Object>) json(); }
    }

    private static Response request(String method, String path, String body, String contentType) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setInstanceFollowRedirects(false);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", contentType);
            try (OutputStream out = c.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        Response r = new Response();
        r.status = c.getResponseCode();
        r.headers = c.getHeaderFields();
        InputStream in = r.status >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in != null) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) != -1) buf.write(chunk, 0, n);
            r.body = new String(buf.toByteArray(), StandardCharsets.UTF_8);
            in.close();
        }
        return r;
    }

    private static Response get(String path) throws Exception { return request("GET", path, null, null); }

    private static Response post(String path, String json) throws Exception {
        return request("POST", path, json, "application/json");
    }

    /** Sends raw bytes over a socket (lets us send things HttpURLConnection refuses to send). */
    private static Response raw(int onPort, String head, byte[] body) throws Exception {
        try (Socket s = new Socket("127.0.0.1", onPort)) {
            s.setSoTimeout(15000);
            OutputStream out = s.getOutputStream();
            out.write(head.getBytes(StandardCharsets.ISO_8859_1));
            if (body != null) out.write(body);
            out.flush();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            InputStream in = s.getInputStream();
            byte[] chunk = new byte[4096];
            int n;
            try {
                while ((n = in.read(chunk)) != -1) buf.write(chunk, 0, n);
            } catch (java.net.SocketTimeoutException e) {
                // fall through with what we have
            }
            String all = new String(buf.toByteArray(), StandardCharsets.UTF_8);
            Response r = new Response();
            int lineEnd = all.indexOf("\r\n");
            String statusLine = lineEnd < 0 ? all : all.substring(0, lineEnd);
            String[] parts = statusLine.split(" ");
            r.status = parts.length > 1 ? Integer.parseInt(parts[1]) : -1;
            int split = all.indexOf("\r\n\r\n");
            r.body = split < 0 ? "" : all.substring(split + 4);
            return r;
        }
    }

    private static Response rawGet(int onPort, String path) throws Exception {
        return raw(onPort, "GET " + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n", null);
    }

    private static Response rawPost(String path, String contentTypeLine, byte[] body) throws Exception {
        String head = "POST " + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n"
                + contentTypeLine + "Content-Length: " + body.length + "\r\n\r\n";
        return raw(port, head, body);
    }

    // ------------------------------------------------------------------
    // helpers to read route answers
    // ------------------------------------------------------------------
    @SuppressWarnings("unchecked")
    private static Map<String, Object> m(Object o) { return (Map<String, Object>) o; }
    @SuppressWarnings("unchecked")
    private static List<Object> l(Object o) { return (List<Object>) o; }
    private static double num(Object o) { return ((Number) o).doubleValue(); }

    private static List<Long> ids(Map<String, Object> route) {
        List<Long> result = new ArrayList<>();
        for (Object loc : l(route.get("locations"))) result.add(((Number) m(loc).get("id")).longValue());
        return result;
    }
    private static List<Long> idList(long... values) {
        List<Long> r = new ArrayList<>();
        for (long v : values) r.add(v);
        return r;
    }
    private static String body(int source, int destination, String extra) {
        return "{\"sourceId\":" + source + ",\"destinationId\":" + destination + (extra.isEmpty() ? "" : "," + extra) + "}";
    }

    // ------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        System.out.println("=== Phase 7: API server tests ===");
        ApiServer server = new ApiServer("127.0.0.1", 0, Paths.get("frontend"), "");
        server.start();
        port = server.getPort();
        base = "http://127.0.0.1:" + port;
        System.out.println("(test server started on " + base + ")");
        try {
            partA_dataEndpoints();
            partB_routes();
            partC_errors();
            partD_staticFiles();
            partE_cors();
            partF_concurrency();
        } finally {
            server.stop();
        }
        System.out.println("\n=== Result: " + passed + " passed, " + failed + " failed ===");
        System.exit(failed == 0 ? 0 : 1);
    }

    // =================================================================
    // A: data endpoints
    // =================================================================
    private static void partA_dataEndpoints() throws Exception {
        System.out.println("\n[A] Data endpoints (GET)");

        Response h = get("/api/health");
        check("GET /api/health -> 200 {status ok, database true}", h.status == 200 && "ok".equals(h.obj().get("status"))
                && Boolean.TRUE.equals(h.obj().get("database")), h.body);
        check("Answer is JSON in UTF-8", "application/json; charset=utf-8".equalsIgnoreCase(h.header("Content-Type")), String.valueOf(h.header("Content-Type")));
        check("Safe headers present: X-Content-Type-Options nosniff, Cache-Control no-store",
                "nosniff".equals(h.header("X-Content-Type-Options")) && "no-store".equals(h.header("Cache-Control")), "");

        Response locs = get("/api/locations");
        List<Object> locations = locs.list();
        Map<String, Object> first = m(locations.get(0));
        check("GET /api/locations -> 200 with 18 locations", locs.status == 200 && locations.size() == 18, "count=" + locations.size());
        check("First location is Main Gate (MAIN_GATE) at x=80, y=320",
                "Main Gate".equals(first.get("name")) && "MAIN_GATE".equals(first.get("type")) && num(first.get("x")) == 80 && num(first.get("y")) == 320, first.toString());
        check("Location answers never contain password or database fields", !locs.body.toLowerCase().contains("password") && !locs.body.contains("jdbc"), "");

        Response paths = get("/api/pathways");
        List<Object> pathways = paths.list();
        int blocked = 0;
        for (Object o : pathways) if ("BLOCKED".equals(m(o).get("status"))) blocked++;
        Map<String, Object> p1 = m(pathways.get(0));
        check("GET /api/pathways -> 200 with 32 pathways, 1 of them BLOCKED", paths.status == 200 && pathways.size() == 32 && blocked == 1, "count=" + pathways.size() + " blocked=" + blocked);
        check("Pathway 1 = Main Gate <-> Administration Block, 88.0 m, HIGH crowd, accessible",
                "Main Gate".equals(p1.get("sourceName")) && "Administration Block".equals(p1.get("destinationName")) && num(p1.get("distance")) == 88.0
                        && "HIGH".equals(p1.get("crowdLevel")) && Boolean.TRUE.equals(p1.get("accessible")), p1.toString());

        Response fac = get("/api/facilities");
        check("GET /api/facilities -> 200 with 32 facilities, each with its location name", fac.status == 200 && fac.list().size() == 32
                && m(fac.list().get(0)).get("locationName") != null, "count=" + fac.list().size());
        Response rest = get("/api/facilities?type=RESTROOM");
        boolean allRestrooms = true;
        for (Object o : rest.list()) if (!"RESTROOM".equals(m(o).get("type"))) allRestrooms = false;
        check("Filter ?type=RESTROOM -> 10 restrooms and nothing else", rest.status == 200 && rest.list().size() == 10 && allRestrooms, "count=" + rest.list().size());
        check("Filter is not case-sensitive (?type=restroom)", get("/api/facilities?type=restroom").list().size() == 10, "");
        check("Filter ?locationId=8 (Central Library) -> 4 facilities", get("/api/facilities?locationId=8").list().size() == 4, "");
        check("Both filters together (?type=RESTROOM&locationId=8) -> 1", get("/api/facilities?type=RESTROOM&locationId=8").list().size() == 1, "");
        Response badType = get("/api/facilities?type=BOGUS");
        check("Unknown facility type -> 400 and the message lists the allowed types", badType.status == 400 && badType.obj().get("error").toString().contains("RESTROOM"), badType.body);
        check("SQL-injection text in the filter -> 400 (and nothing is executed)", get("/api/facilities?type=%27%20OR%20%271%27%3D%271").status == 400, "");
        check("locationId that is not a number -> 400", get("/api/facilities?locationId=abc").status == 400, "");
        check("Broken percent escape (?type=%ZZ) -> 400", get("/api/facilities?type=%ZZ").status == 400, "");

        Response sum = get("/api/summary");
        Map<String, Object> s = sum.obj();
        Map<String, Object> sp = m(s.get("pathways"));
        Map<String, Object> sc = m(s.get("crowd"));
        check("GET /api/summary: 18 locations, 32 pathways (29 open, 2 restricted, 1 blocked, 3 not accessible)",
                sum.status == 200 && num(s.get("locations")) == 18 && num(sp.get("total")) == 32 && num(sp.get("open")) == 29
                        && num(sp.get("restricted")) == 2 && num(sp.get("blocked")) == 1 && num(sp.get("notAccessible")) == 3, sum.body);
        check("Summary crowd counts LOW 21, MEDIUM 8, HIGH 3 and facilities 32 (30 available)",
                num(sc.get("LOW")) == 21 && num(sc.get("MEDIUM")) == 8 && num(sc.get("HIGH")) == 3
                        && num(m(s.get("facilities")).get("total")) == 32 && num(m(s.get("facilities")).get("available")) == 30, sum.body);

        Response head = request("HEAD", "/api/locations", null, null);
        check("HEAD /api/locations -> 200 with no body", head.status == 200 && head.body.isEmpty(), "");
    }

    // =================================================================
    // B: route endpoints
    // =================================================================
    private static void partB_routes() throws Exception {
        System.out.println("\n[B] Route endpoints (POST)");

        // Hand-checked on the real data in Phases 5 and 6
        Response sh = post("/api/routes/shortest", body(1, 8, ""));
        Map<String, Object> r = m(sh.obj().get("route"));
        Map<String, Object> sum = m(r.get("summary"));
        check("shortest Main Gate -> Library: [1,2,8], 202.7 m, 169 s, Dijkstra",
                sh.status == 200 && Boolean.TRUE.equals(r.get("found")) && ids(r).equals(idList(1, 2, 8)) && num(sum.get("totalDistance")) == 202.7
                        && num(sum.get("totalTimeSeconds")) == 169 && "Dijkstra".equals(r.get("algorithm")), sh.body.length() > 300 ? sh.body.substring(0, 300) : sh.body);
        check("Answer includes names of source and destination", "Main Gate".equals(m(sh.obj().get("source")).get("name"))
                && "Central Library".equals(m(sh.obj().get("destination")).get("name")), "");
        check("Answer has 2 segments, 3 locations, 1 intermediate location, 2.8 walking minutes",
                l(r.get("segments")).size() == 2 && num(sum.get("locationCount")) == 3 && num(sum.get("intermediateLocations")) == 1
                        && num(sum.get("walkingMinutes")) == 2.8, sum.toString());
        Map<String, Object> seg0 = m(l(r.get("segments")).get(0));
        check("First segment: Main Gate -> Administration Block, 88.0 m, HIGH crowd, OPEN",
                "Main Gate".equals(seg0.get("fromName")) && "Administration Block".equals(seg0.get("toName")) && num(seg0.get("distance")) == 88.0
                        && "HIGH".equals(seg0.get("crowdLevel")) && "OPEN".equals(seg0.get("status")), seg0.toString());
        check("Crowd counts of the route: 1 HIGH, 1 MEDIUM, 0 LOW", num(m(sum.get("crowd")).get("HIGH")) == 1 && num(m(sum.get("crowd")).get("MEDIUM")) == 1
                && num(m(sum.get("crowd")).get("LOW")) == 0, m(sum.get("crowd")).toString());

        Response as = post("/api/routes/astar", body(1, 8, ""));
        Map<String, Object> ar = m(as.obj().get("route"));
        check("astar Main Gate -> Library: same cost 202.7 m, algorithm A*", as.status == 200 && num(m(ar.get("summary")).get("totalDistance")) == 202.7
                && "A*".equals(ar.get("algorithm")), "");

        Response ctx = post("/api/routes/context-aware", body(6, 8, ""));
        Map<String, Object> cr = m(ctx.obj().get("route"));
        check("context-aware CS Block -> Library: [6,7,9,8], 278.0 m, contextual cost 281.0, recommendable",
                ctx.status == 200 && ids(cr).equals(idList(6, 7, 9, 8)) && num(m(cr.get("summary")).get("totalDistance")) == 278.0
                        && num(m(cr.get("evaluation")).get("contextualCost")) == 281.0 && Boolean.TRUE.equals(m(cr.get("evaluation")).get("recommendable")), "");

        Response blockedSh = post("/api/routes/shortest", body(6, 8, ""));
        Map<String, Object> br = m(blockedSh.obj().get("route"));
        check("plain shortest CS Block -> Library uses the BLOCKED path [6,8] and is flagged NOT recommendable",
                ids(br).equals(idList(6, 8)) && Boolean.FALSE.equals(m(br.get("evaluation")).get("recommendable"))
                        && l(m(br.get("evaluation")).get("violations")).toString().contains("BLOCKED") && m(br.get("evaluation")).get("contextualCost") == null, m(br.get("evaluation")).toString());

        Response cmp = post("/api/routes/compare", body(6, 8, ""));
        Map<String, Object> c = cmp.obj();
        Map<String, Object> routes = m(c.get("routes"));
        check("compare CS Block -> Library: recommended 'contextAware', routes NOT identical",
                cmp.status == 200 && "contextAware".equals(c.get("recommended")) && Boolean.FALSE.equals(c.get("routesIdentical")), "");
        check("compare returns shortest, astar and contextAware routes", routes.containsKey("shortest") && routes.containsKey("astar") && routes.containsKey("contextAware"), routes.keySet().toString());
        check("compare: shortest and A* have equal distance (75.0), the context-aware route is 278.0",
                num(m(m(routes.get("shortest")).get("summary")).get("totalDistance")) == 75.0
                        && num(m(m(routes.get("astar")).get("summary")).get("totalDistance")) == 75.0
                        && num(m(m(routes.get("contextAware")).get("summary")).get("totalDistance")) == 278.0, "");
        check("compare explanation mentions '203.0 m longer' and the BLOCKED pathway", l(c.get("explanation")).toString().contains("203.0 m longer")
                && l(c.get("explanation")).toString().contains("BLOCKED"), l(c.get("explanation")).toString());

        Response same = post("/api/routes/compare", body(1, 8, ""));
        check("compare Main Gate -> Library: routes ARE identical and the explanation says so (no invented difference)",
                Boolean.TRUE.equals(same.obj().get("routesIdentical")) && l(same.obj().get("explanation")).toString().contains("both are the same"), "");

        Response acc = post("/api/routes/context-aware", body(5, 6, "\"requireAccessible\":true"));
        check("requireAccessible: Electrical -> CS Block takes [5,2,8,9,7,6], 482.6 m",
                ids(m(acc.obj().get("route"))).equals(idList(5, 2, 8, 9, 7, 6)) && num(m(m(acc.obj().get("route")).get("summary")).get("totalDistance")) == 482.6
                        && Boolean.TRUE.equals(m(acc.obj().get("preferences")).get("requireAccessible")), "");
        check("Without the accessibility option the direct path [5,6] is used", ids(m(post("/api/routes/context-aware", body(5, 6, "")).obj().get("route"))).equals(idList(5, 6)), "");

        Response time = post("/api/routes/shortest", body(1, 8, "\"metric\":\"time\""));
        check("metric 'time' (lower case) is accepted: preferences.metric TIME, 169 s", "TIME".equals(m(time.obj().get("preferences")).get("metric"))
                && num(m(m(time.obj().get("route")).get("summary")).get("totalTimeSeconds")) == 169, "");

        Response w0 = post("/api/routes/compare", body(1, 7, "\"crowdWeight\":0"));
        Response w1 = post("/api/routes/compare", body(1, 7, ""));
        check("crowdWeight 0: Main Gate -> Mechanical Block routes are identical", Boolean.TRUE.equals(w0.obj().get("routesIdentical")), "");
        check("default crowdWeight: the context route [1,4,5,6,7] differs from shortest [1,2,8,9,7]",
                Boolean.FALSE.equals(w1.obj().get("routesIdentical"))
                        && ids(m(m(w1.obj().get("routes")).get("contextAware"))).equals(idList(1, 4, 5, 6, 7))
                        && ids(m(m(w1.obj().get("routes")).get("shortest"))).equals(idList(1, 2, 8, 9, 7)), "");
        check("preferences echo the effective crowd penalties 0 / 3 / 8 in metre-equivalents",
                num(m(m(w1.obj().get("preferences")).get("crowdPenalties")).get("HIGH")) == 8
                        && "metre-equivalents".equals(m(w1.obj().get("preferences")).get("penaltyUnit")), m(w1.obj().get("preferences")).toString());

        // The client can NOT switch on restricted paths
        Response restricted = post("/api/routes/context-aware", body(11, 13, "\"allowRestricted\":true"));
        Map<String, Object> rr = m(restricted.obj().get("route"));
        check("allowRestricted:true from a client is IGNORED: route stays [11,14,13] (212.1 m), not the restricted [11,13]",
                restricted.status == 200 && ids(rr).equals(idList(11, 14, 13)) && num(m(rr.get("summary")).get("totalDistance")) == 212.1, "");
        check("...and the answer says so (warning) and reports restrictedPathsPermitted=false",
                l(restricted.obj().get("warnings")).size() == 1 && l(restricted.obj().get("warnings")).get(0).toString().contains("allowRestricted")
                        && Boolean.FALSE.equals(m(restricted.obj().get("preferences")).get("restrictedPathsPermitted")), restricted.body.length() > 200 ? "" : restricted.body);
        check("Without the field there is no warning", l(post("/api/routes/context-aware", body(11, 13, "")).obj().get("warnings")).isEmpty(), "");

        // The client can NOT supply costs
        Response inject = post("/api/routes/shortest", body(1, 8, "\"distance\":1,\"cost\":0,\"penalties\":{\"HIGH\":0},\"pathways\":[]"));
        check("Extra fields such as distance/cost/penalties are ignored: result is still 202.7 m",
                inject.status == 200 && num(m(m(inject.obj().get("route")).get("summary")).get("totalDistance")) == 202.7, "");

        Response self = post("/api/routes/context-aware", body(3, 3, ""));
        check("Same source and destination: found, 0.0 m, 1 location", self.status == 200 && Boolean.TRUE.equals(m(self.obj().get("route")).get("found"))
                && num(m(m(self.obj().get("route")).get("summary")).get("totalDistance")) == 0 && l(m(self.obj().get("route")).get("locations")).size() == 1, "");
        check("A whole number written as 8.0 is accepted", post("/api/routes/shortest", "{\"sourceId\":1.0,\"destinationId\":8.0}").status == 200, "");
    }

    // =================================================================
    // C: errors
    // =================================================================
    private static void partC_errors() throws Exception {
        System.out.println("\n[C] Error handling");

        String[] badBodies = {
                "{\"destinationId\":8}",                                   // missing sourceId
                "{\"sourceId\":1}",                                        // missing destinationId
                "{\"sourceId\":\"1\",\"destinationId\":8}",                // text instead of number
                "{\"sourceId\":-1,\"destinationId\":8}",                   // negative
                "{\"sourceId\":0,\"destinationId\":8}",                    // zero
                "{\"sourceId\":1.5,\"destinationId\":8}",                  // decimal
                "{\"sourceId\":null,\"destinationId\":8}",                 // null
                "{\"sourceId\":99999999999,\"destinationId\":8}",          // too large
                "{\"sourceId\":true,\"destinationId\":8}",                 // boolean
                "{\"sourceId\":[1],\"destinationId\":8}",                  // array
                "{\"sourceId\":1,\"destinationId\":8,\"metric\":\"FAST\"}",
                "{\"sourceId\":1,\"destinationId\":8,\"metric\":5}",
                "{\"sourceId\":1,\"destinationId\":8,\"crowdWeight\":11}",
                "{\"sourceId\":1,\"destinationId\":8,\"crowdWeight\":-1}",
                "{\"sourceId\":1,\"destinationId\":8,\"crowdWeight\":\"2\"}",
                "{\"sourceId\":1,\"destinationId\":8,\"requireAccessible\":\"yes\"}",
                "{\"sourceId\":1,\"destinationId\":8,\"allowRestricted\":\"true\"}",
                "{\"sourceId\":1,\"destinationId\":8,}",                   // trailing comma
                "{\"sourceId\":1,\"sourceId\":2,\"destinationId\":8}",      // duplicate key
                "{not json}", "", "   ", "[]", "[1,2]", "42", "\"text\"", "null", "{",
        };
        int wrong = 0;
        StringBuilder which = new StringBuilder();
        for (String b : badBodies) {
            Response r = post("/api/routes/shortest", b);
            boolean shapeOk = r.status == 400 && r.obj().containsKey("error") && num(r.obj().get("status")) == 400
                    && "application/json; charset=utf-8".equalsIgnoreCase(r.header("Content-Type"));
            if (!shapeOk) { wrong++; which.append(" [").append(b).append(" -> ").append(r.status).append("]"); }
        }
        check("All " + badBodies.length + " invalid request bodies -> 400 with a JSON {error, status}", wrong == 0, which.toString());

        Response badUtf8 = rawPost("/api/routes/shortest", "Content-Type: application/json\r\n", new byte[] { '{', '"', 'a', '"', ':', (byte) 0xFF, (byte) 0xFE, '}' });
        check("Invalid UTF-8 bytes in the body -> 400", badUtf8.status == 400, "status=" + badUtf8.status);

        Response u1 = post("/api/routes/shortest", body(999, 8, ""));
        Response u2 = post("/api/routes/shortest", body(1, 999, ""));
        check("Unknown source id 999 -> 404 with a clear message", u1.status == 404 && u1.obj().get("error").toString().contains("Starting location"), u1.body);
        check("Unknown destination id 999 -> 404 with a clear message", u2.status == 404 && u2.obj().get("error").toString().contains("Destination"), u2.body);
        check("The same 404 for the other three route endpoints", post("/api/routes/astar", body(999, 8, "")).status == 404
                && post("/api/routes/context-aware", body(999, 8, "")).status == 404 && post("/api/routes/compare", body(999, 8, "")).status == 404, "");

        Response text = request("POST", "/api/routes/shortest", body(1, 8, ""), "text/plain");
        check("Content-Type text/plain -> 415", text.status == 415, "status=" + text.status);
        Response form = request("POST", "/api/routes/shortest", body(1, 8, ""), "application/x-www-form-urlencoded");
        check("Content-Type form-urlencoded -> 415 (blocks simple cross-site form posts)", form.status == 415, "status=" + form.status);
        Response noType = rawPost("/api/routes/shortest", "", body(1, 8, "").getBytes(StandardCharsets.UTF_8));
        check("Missing Content-Type -> 415", noType.status == 415, "status=" + noType.status);
        Response charset = request("POST", "/api/routes/shortest", body(1, 8, ""), "application/json; charset=utf-8");
        check("Content-Type 'application/json; charset=utf-8' is accepted", charset.status == 200, "status=" + charset.status);

        StringBuilder big = new StringBuilder("{\"sourceId\":1,\"destinationId\":8,\"pad\":\"");
        for (int i = 0; i < 20000; i++) big.append('x');
        big.append("\"}");
        Response tooBig = post("/api/routes/shortest", big.toString());
        check("Body of about 20 KB (limit 16 KB) -> 413", tooBig.status == 413, "status=" + tooBig.status);

        Response getRoute = get("/api/routes/shortest");
        check("GET on a POST-only endpoint -> 405 with 'Allow: POST'", getRoute.status == 405 && "POST".equals(getRoute.header("Allow")), "allow=" + getRoute.header("Allow"));
        check("PUT on a route endpoint -> 405", request("PUT", "/api/routes/shortest", "{}", "application/json").status == 405, "");
        Response postLoc = post("/api/locations", "{}");
        check("POST /api/locations -> 405 with Allow containing GET", postLoc.status == 405 && postLoc.header("Allow").contains("GET"), "allow=" + postLoc.header("Allow"));
        check("DELETE /api/locations -> 405", request("DELETE", "/api/locations", null, null).status == 405, "");
        check("OPTIONS without CORS configured -> 405", request("OPTIONS", "/api/locations", null, null).status == 405, "");

        String[] unknown = { "/api/nothing", "/api/routes/nothing", "/api/routes", "/api/routes/", "/api", "/api/", "/api/locationsX", "/api/health/extra", "/api/admin/login" };
        int notJson404 = 0;
        StringBuilder bad = new StringBuilder();
        for (String u : unknown) {
            Response r = get(u);
            boolean ok = r.status == 404 && r.header("Content-Type") != null && r.header("Content-Type").startsWith("application/json") && r.obj().containsKey("error");
            if (!ok) { notJson404++; bad.append(" [").append(u).append(" -> ").append(r.status).append("]"); }
        }
        check("Unknown API addresses -> JSON 404 (" + unknown.length + " tried)", notJson404 == 0, bad.toString());

        // No technical detail in any error answer
        String[] probes = { "/api/nothing", "/api/facilities?type=BOGUS" };
        boolean leak = false;
        List<String> bodies = new ArrayList<>();
        for (String p : probes) bodies.add(get(p).body);
        bodies.add(post("/api/routes/shortest", "{bad").body);
        bodies.add(post("/api/routes/shortest", body(999, 8, "")).body);
        bodies.add(getRoute.body);
        for (String b : bodies) {
            String low = b.toLowerCase();
            if (low.contains("exception") || low.contains("jdbc") || low.contains("password") || low.contains(".java") || low.contains("\tat ") || low.contains("sql")) leak = true;
        }
        check("Error answers contain no stack traces, SQL, JDBC or password text", !leak, "");
    }

    // =================================================================
    // D: static files (the website) and path attacks
    // =================================================================
    private static void partD_staticFiles() throws Exception {
        System.out.println("\n[D] Website files and path-traversal attacks");

        Response root = get("/");
        check("GET / -> 200 text/html containing the application name", root.status == 200 && root.header("Content-Type").startsWith("text/html")
                && root.body.contains("Campus Route Optimizer"), "");
        check("Security headers on pages: nosniff, X-Frame-Options DENY, Content-Security-Policy",
                "nosniff".equals(root.header("X-Content-Type-Options")) && "DENY".equals(root.header("X-Frame-Options"))
                        && root.header("Content-Security-Policy") != null && root.header("Content-Security-Policy").contains("default-src 'self'"), "");
        check("GET /index.html -> 200", get("/index.html").status == 200, "");
        Response css = get("/css/style.css");
        check("GET /css/style.css -> 200 text/css", css.status == 200 && css.header("Content-Type").startsWith("text/css"), "");
        Response js = get("/js/app.js");
        check("GET /js/app.js -> 200 JavaScript", js.status == 200 && js.header("Content-Type").startsWith("application/javascript"), "");
        check("HEAD / -> 200 without a body", request("HEAD", "/", null, null).status == 200 && request("HEAD", "/", null, null).body.isEmpty(), "");
        Response missing = get("/nope.html");
        check("Missing file -> 404 (plain text, no file system details)", missing.status == 404 && missing.body.equals("Not found"), missing.body);
        Response postRoot = request("POST", "/", "x", "text/plain");
        check("POST / -> 405", postRoot.status == 405, "");

        // Attack paths. config.properties exists next to the frontend folder, so a leak would be visible.
        String[] attacks = {
                "/../backend/config.properties", "/%2e%2e/backend/config.properties", "/%2E%2E%2Fbackend%2Fconfig.properties",
                "/css/../../backend/config.properties", "/..%5Cbackend%5Cconfig.properties", "/....//backend/config.properties",
                "/%00", "/index.html%00.css", "/.git/config", "/css/.hidden", "/C:/Windows/win.ini", "/backend/config.properties",
                "//etc/passwd", "/..;/..;/backend/config.properties", "/%252e%252e/backend/config.properties",
                "/index.html::$DATA", "/..\\backend\\config.properties", "/css/%2e%2e/%2e%2e/backend/config.properties",
        };
        int leaked = 0, ok200 = 0;
        StringBuilder detail = new StringBuilder();
        for (String a : attacks) {
            Response r;
            try {
                r = rawGet(port, a);
            } catch (Exception e) {
                continue;                                  // the connection was refused: also safe
            }
            String low = r.body.toLowerCase();
            if (low.contains("db.user") || low.contains("db.url") || low.contains("db.password") || low.contains("root:")) { leaked++; detail.append(" LEAK[").append(a).append("]"); }
            if (r.status == 200) { ok200++; detail.append(" 200[").append(a).append("]"); }
        }
        check("None of " + attacks.length + " path-traversal attacks returns a file (all refused)", leaked == 0 && ok200 == 0, detail.toString());

        // Defence in depth: even if the frontend folder were set to the WRONG folder (backend), secrets are not served.
        ApiServer wrong = new ApiServer("127.0.0.1", 0, Paths.get("backend"), "");
        wrong.start();
        try {
            Response cfg = rawGet(wrong.getPort(), "/config.properties");
            Response src = rawGet(wrong.getPort(), "/src/Main.java");
            Response ex = rawGet(wrong.getPort(), "/config.properties.example");
            check("Frontend folder set to 'backend' by mistake: config.properties, .java and .example files are STILL not served (file-type allow-list)",
                    cfg.status == 404 && src.status == 404 && ex.status == 404 && !cfg.body.contains("db."), "statuses " + cfg.status + "/" + src.status + "/" + ex.status);
        } finally {
            wrong.stop();
        }

        // Symbolic link pointing OUT of the frontend folder
        Path temp = Files.createTempDirectory("campus-static-test");
        Path outsideFile = null;
        try {
            Files.write(temp.resolve("index.html"), "<html>inside</html>".getBytes(StandardCharsets.UTF_8));
            // The secret file has an ALLOWED type (.txt) and lives OUTSIDE the website folder, so only the
            // "real path must stay inside the folder" rule can stop it (not the file-type allow-list).
            Path outside = Files.createTempDirectory("campus-outside-test");
            Files.write(outside.resolve("secret.txt"), "TOP-SECRET-OUTSIDE-DATA".getBytes(StandardCharsets.UTF_8));
            outsideFile = outside.resolve("secret.txt");
            boolean linkCreated = true;
            try {
                Files.createSymbolicLink(temp.resolve("link.txt"), outsideFile);
            } catch (Exception | Error e) {
                linkCreated = false;
            }
            ApiServer linked = new ApiServer("127.0.0.1", 0, temp, "");
            linked.start();
            try {
                Response inside = rawGet(linked.getPort(), "/");
                check("Temporary website folder: its own index.html is served", inside.status == 200 && inside.body.contains("inside"), "status=" + inside.status);
                if (linkCreated) {
                    Response link = rawGet(linked.getPort(), "/link.txt");
                    check("A symbolic link inside the website folder that points OUTSIDE it (to a .txt file) is refused (404, nothing leaked)",
                            link.status == 404 && !link.body.contains("TOP-SECRET"), "status=" + link.status);
                } else {
                    System.out.println("        info: symbolic links cannot be created here (normal on Windows without admin rights): symlink test skipped");
                }
            } finally {
                linked.stop();
            }
        } finally {
            Files.deleteIfExists(temp.resolve("link.txt"));
            Files.deleteIfExists(temp.resolve("index.html"));
            Files.deleteIfExists(temp);
            if (outsideFile != null) {
                Files.deleteIfExists(outsideFile);
                Files.deleteIfExists(outsideFile.getParent());
            }
        }

        ApiServer none = new ApiServer("127.0.0.1", 0, Paths.get("folder-that-does-not-exist"), "");
        none.start();
        try {
            check("A missing website folder gives 404, not a crash", rawGet(none.getPort(), "/").status == 404, "");
            check("...and the API still works", rawGet(none.getPort(), "/api/health").status == 200, "");
        } finally {
            none.stop();
        }
    }

    // =================================================================
    // E: CORS
    // =================================================================
    private static void partE_cors() throws Exception {
        System.out.println("\n[E] CORS (only when configured)");
        check("Default server sends NO Access-Control-Allow-Origin header", get("/api/locations").header("Access-Control-Allow-Origin") == null, "");

        ApiServer cors = new ApiServer("127.0.0.1", 0, Paths.get("frontend"), "http://localhost:5500");
        cors.start();
        try {
            Response r = raw(cors.getPort(), "GET /api/locations HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n", null);
            check("With cors.allowed.origin set, normal answers carry that exact origin (not '*')", r.status == 200, "");
            HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + cors.getPort() + "/api/locations").openConnection();
            c.getResponseCode();
            check("Header Access-Control-Allow-Origin = http://localhost:5500", "http://localhost:5500".equals(c.getHeaderField("Access-Control-Allow-Origin")), String.valueOf(c.getHeaderField("Access-Control-Allow-Origin")));
            Response pre = raw(cors.getPort(), "OPTIONS /api/routes/shortest HTTP/1.1\r\nHost: x\r\nOrigin: http://localhost:5500\r\nAccess-Control-Request-Method: POST\r\nConnection: close\r\n\r\n", null);
            check("Preflight OPTIONS -> 204", pre.status == 204, "status=" + pre.status);
        } finally {
            cors.stop();
        }
    }

    // =================================================================
    // F: many requests at once
    // =================================================================
    private static void partF_concurrency() throws Exception {
        System.out.println("\n[F] 24 simultaneous requests");
        ExecutorService pool = Executors.newFixedThreadPool(12);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            final int n = i;
            futures.add(pool.submit(new Callable<String>() {
                @Override
                public String call() throws Exception {
                    Response r = (n % 2 == 0) ? post("/api/routes/compare", body(6, 8, "")) : post("/api/routes/compare", body(1, 7, ""));
                    return r.status + "|" + r.body;
                }
            }));
        }
        List<String> evenBodies = new ArrayList<>(), oddBodies = new ArrayList<>();
        boolean allOk = true;
        for (int i = 0; i < futures.size(); i++) {
            String result = futures.get(i).get();
            if (!result.startsWith("200|")) allOk = false;
            (i % 2 == 0 ? evenBodies : oddBodies).add(result);
        }
        pool.shutdown();
        boolean evenSame = new java.util.HashSet<>(evenBodies).size() == 1;
        boolean oddSame = new java.util.HashSet<>(oddBodies).size() == 1;
        check("All 24 parallel compare requests answered 200", allOk, "");
        check("Parallel requests never mix up their answers (identical requests give identical bodies)", evenSame && oddSame, "");
    }
}
