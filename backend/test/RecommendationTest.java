import graph.CampusGraph;
import model.AvailabilityStatus;
import model.CrowdLevel;
import model.Facility;
import model.FacilityType;
import model.Location;
import model.LocationType;
import model.Pathway;
import model.PathwayStatus;
import service.RecommendationService;
import service.RecommendationService.Recommendation;
import service.RecommendationService.Result;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * RecommendationTest - tests the facility recommendation RULES. No database is needed:
 * the test builds a small campus in memory.
 * Run:  scripts\test-recommendations.bat   (compile first with scripts\compile.bat)
 *
 * The small test campus (distances in metres):
 *
 *   Gate(5) --120-- Cafeteria(2) --80-- Library(1) --100(no steps)-- Lab Block(3) --400-- Far Hostel(4)
 *   Gate(5) --30 BLOCKED-- Library(1)           Cafeteria(2) --90 (no steps)-- Lab Block(3)
 *   Closed Annex(6) is INACTIVE.
 */
public class RecommendationTest {

    private static int passed = 0;
    private static int failed = 0;

    private static void check(String name, boolean ok, String detail) {
        if (ok) { passed++; } else { failed++; }
        System.out.println("  " + (ok ? "PASS" : "FAIL") + "  " + name + (detail.isEmpty() ? "" : "  -> " + detail));
    }

    private static Location loc(int id, String name, LocationType type, int x, boolean active) {
        return new Location(id, name, type, "test", x, 0, active);
    }

    private static Pathway path(int id, int a, int b, double dist, PathwayStatus status, boolean accessible) {
        return new Pathway(id, a, b, dist, (int) (dist / 1.2), CrowdLevel.LOW, status, accessible);
    }

    private static Facility fac(int id, String name, FacilityType type, int locationId, AvailabilityStatus a) {
        return new Facility(id, name, type, locationId, "test", a);
    }

    private static Recommendation find(Result r, FacilityType type) {
        for (Recommendation x : r.recommendations) if (x.facility.getType() == type) return x;
        return null;
    }

    private static boolean has(Result r, String facilityName) {
        for (Recommendation x : r.recommendations) if (x.facility.getName().equals(facilityName)) return true;
        return false;
    }

    public static void main(String[] args) {
        List<Location> locations = Arrays.asList(
                loc(1, "Library", LocationType.LIBRARY, 0, true),
                loc(2, "Cafeteria", LocationType.CAFETERIA, 80, true),
                loc(3, "Lab Block", LocationType.ACADEMIC, 100, true),
                loc(4, "Far Hostel", LocationType.HOSTEL, 500, true),
                loc(5, "Gate", LocationType.MAIN_GATE, 200, true),
                loc(6, "Closed Annex", LocationType.BUILDING, 10, false));
        List<Pathway> pathways = Arrays.asList(
                path(1, 1, 2, 80, PathwayStatus.OPEN, true),
                path(2, 1, 3, 100, PathwayStatus.OPEN, false),
                path(3, 2, 3, 90, PathwayStatus.OPEN, false),
                path(4, 3, 4, 400, PathwayStatus.OPEN, true),
                path(5, 2, 5, 120, PathwayStatus.OPEN, true),
                path(6, 1, 5, 30, PathwayStatus.BLOCKED, true));
        CampusGraph graph = new CampusGraph(locations, pathways);

        List<Facility> facilities = Arrays.asList(
                fac(1, "Library Restroom", FacilityType.RESTROOM, 1, AvailabilityStatus.AVAILABLE),
                fac(2, "Library Water", FacilityType.DRINKING_WATER, 1, AvailabilityStatus.UNDER_MAINTENANCE),
                fac(3, "Cafe Snacks", FacilityType.CAFETERIA, 2, AvailabilityStatus.AVAILABLE),
                fac(4, "Cafe Restroom", FacilityType.RESTROOM, 2, AvailabilityStatus.AVAILABLE),
                fac(5, "Lab Computers", FacilityType.COMPUTER_LAB, 3, AvailabilityStatus.AVAILABLE),
                fac(6, "Hostel Water", FacilityType.DRINKING_WATER, 4, AvailabilityStatus.AVAILABLE),
                fac(7, "Gate ATM", FacilityType.ATM, 5, AvailabilityStatus.AVAILABLE),
                fac(8, "Gate Print Shop", FacilityType.PRINT_SHOP, 5, AvailabilityStatus.AVAILABLE),
                fac(9, "Annex Study Room", FacilityType.STUDY_AREA, 6, AvailabilityStatus.AVAILABLE));

        RecommendationService service = new RecommendationService();

        System.out.println("=== Recommendation rules (destination = Library) ===");
        Result lib = service.recommend(graph, facilities, 1, null, false);

        Recommendation restroom = find(lib, FacilityType.RESTROOM);
        check("restroom inside the library is recommended", restroom != null && restroom.facility.getName().equals("Library Restroom"), "");
        check("it is marked 'at destination' with distance 0", restroom != null && restroom.atDestination && restroom.distanceMetres == 0.0, "");
        check("only ONE restroom is shown (best per type)", count(lib, FacilityType.RESTROOM) == 1, "");

        Recommendation cafe = find(lib, FacilityType.CAFETERIA);
        check("cafeteria recommended, walking distance 80 m", cafe != null && cafe.distanceMetres == 80.0, cafe == null ? "missing" : "" + cafe.distanceMetres);
        check("cafeteria score = 80 + 25 x rank 0 = 80", cafe != null && cafe.score == 80.0, cafe == null ? "missing" : "" + cafe.score);

        check("computer lab recommended (100 m, direct path)", find(lib, FacilityType.COMPUTER_LAB) != null
                && find(lib, FacilityType.COMPUTER_LAB).distanceMetres == 100.0, "");
        check("unavailable facility is NOT recommended", find(lib, FacilityType.DRINKING_WATER) == null, "");
        check("a notice explains the unavailable facility", lib.notices.size() == 1 && lib.notices.get(0).contains("Library Water")
                && lib.notices.get(0).contains("under maintenance"), lib.notices.toString());
        check("facility farther than 250 m is ignored (hostel, 500 m)", !has(lib, "Hostel Water"), "");
        check("irrelevant type is ignored (ATM for a library)", !has(lib, "Gate ATM"), "");
        Recommendation print = find(lib, FacilityType.PRINT_SHOP);
        check("blocked pathway is not used: gate is 200 m via the cafeteria, not 30 m", print != null && print.distanceMetres == 200.0,
                print == null ? "missing" : "" + print.distanceMetres);
        check("facility in an INACTIVE location is ignored", !has(lib, "Annex Study Room"), "");

        boolean sorted = true;
        for (int i = 1; i < lib.recommendations.size(); i++) {
            if (lib.recommendations.get(i - 1).score > lib.recommendations.get(i).score) sorted = false;
        }
        check("recommendations are sorted by score (best first)", sorted, "");
        check("never more than " + RecommendationService.MAX_RESULTS + " results", lib.recommendations.size() <= RecommendationService.MAX_RESULTS, "");
        boolean explained = !lib.recommendations.isEmpty();
        for (Recommendation r : lib.recommendations) {
            if (r.explanation == null || !r.explanation.startsWith("Rule:") || !r.explanation.contains(r.facility.getName())) explained = false;
        }
        check("every recommendation has an explanation", explained, "");

        System.out.println("\n=== Accessibility ===");
        Result step = service.recommend(graph, facilities, 1, null, true);
        check("step-free: lab is reachable only through steps -> not recommended", find(step, FacilityType.COMPUTER_LAB) == null, "");
        check("step-free: cafeteria (step-free path) is still recommended", find(step, FacilityType.CAFETERIA) != null, "");

        System.out.println("\n=== Route awareness (user walks Gate -> Library) ===");
        Result onRoute = service.recommend(graph, facilities, 1, 5, false);
        Recommendation cafeOnRoute = find(onRoute, FacilityType.CAFETERIA);
        check("cafeteria is on the route (Gate-Cafeteria-Library)", cafeOnRoute != null && cafeOnRoute.onRoute, "");
        check("on-route facility needs no detour: score 0", cafeOnRoute != null && cafeOnRoute.score == 0.0, cafeOnRoute == null ? "" : "" + cafeOnRoute.score);
        check("explanation mentions the route", cafeOnRoute != null && cafeOnRoute.explanation.contains("on your route"), "");
        check("cafeteria without a known route is NOT marked on-route", cafe != null && !cafe.onRoute, "");

        System.out.println("\n=== Other destination types ===");
        Result gate = service.recommend(graph, facilities, 5, null, false);
        check("at the main gate the ATM is recommended first", !gate.recommendations.isEmpty()
                && gate.recommendations.get(0).facility.getName().equals("Gate ATM"), "");
        Result hostel = service.recommend(graph, facilities, 4, null, false);
        check("hostel: its own water point is recommended", has(hostel, "Hostel Water"), "");
        check("hostel: nothing else is within 250 m, so exactly 1 result", hostel.recommendations.size() == 1, "" + hostel.recommendations.size());
        Result none = service.recommend(graph, new ArrayList<Facility>(), 1, null, false);
        check("no facilities at all -> empty list, no error", none.recommendations.isEmpty() && none.notices.isEmpty(), "");

        System.out.println("\n=== Result: " + passed + " passed, " + failed + " failed ===");
        if (failed > 0) System.exit(1);
    }

    private static int count(Result r, FacilityType type) {
        int n = 0;
        for (Recommendation x : r.recommendations) if (x.facility.getType() == type) n++;
        return n;
    }
}
