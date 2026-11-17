package service;

import algorithm.ContextAwareRouter;
import dao.FacilityDAO;
import graph.CampusGraph;
import model.AvailabilityStatus;
import model.Facility;
import model.FacilityType;
import model.Location;
import model.LocationType;
import model.RouteResult;
import model.RoutingPreferences;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RecommendationService - a RULE-BASED facility recommender.
 *
 * *** This is NOT machine learning. *** There is no training and no data-driven model. Every
 * recommendation comes from the fixed, readable rules below, so each one can be explained.
 *
 * THE RULES
 *   Rule 1 (relevance)    Which facility TYPES are useful depends on the TYPE of the destination
 *                         (see USEFUL_TYPES). Example: library -> cafeteria, restroom, drinking water ...
 *                         The order of each list is the priority: the first type matters most.
 *   Rule 2 (availability) Only facilities marked AVAILABLE are recommended. A relevant facility that
 *                         is nearby but unavailable produces a short NOTICE instead.
 *   Rule 3 (nearness)     "Nearby" means WALKING distance on the campus graph from the destination,
 *                         not a straight line. The walk must follow the hard routing rules (no blocked
 *                         or restricted pathways; step-free only if the user asked for that).
 *                         Facilities farther than MAX_DISTANCE_METRES are ignored, unless they are
 *                         on the user's route (then no detour is needed).
 *   Rule 4 (one per type) Only the best facility of each useful type is shown, so the list is varied.
 *   Rule 5 (ranking)      score = effective distance + RANK_WEIGHT x priority position. Lower is better.
 *                         "Effective distance" is 0 for a facility on the user's route (no detour).
 *                         Both numbers are in metre-equivalents and are illustrative choices.
 */
public class RecommendationService {

    /** Facilities farther than this (walking metres from the destination) are not "nearby". Adjustable. */
    public static final double MAX_DISTANCE_METRES = 250.0;
    /** How strongly the priority order of Rule 1 counts, in metre-equivalents per position. Adjustable. */
    public static final double RANK_WEIGHT = 25.0;
    /** The most recommendations returned. */
    public static final int MAX_RESULTS = 6;

    /** Rule 1: useful facility types for each kind of destination, most important first. */
    private static final Map<LocationType, List<FacilityType>> USEFUL_TYPES = new EnumMap<>(LocationType.class);
    static {
        USEFUL_TYPES.put(LocationType.LIBRARY,   Arrays.asList(FacilityType.CAFETERIA, FacilityType.RESTROOM,
                FacilityType.DRINKING_WATER, FacilityType.COMPUTER_LAB, FacilityType.PRINT_SHOP, FacilityType.STUDY_AREA));
        USEFUL_TYPES.put(LocationType.ACADEMIC,  Arrays.asList(FacilityType.DRINKING_WATER, FacilityType.RESTROOM,
                FacilityType.COMPUTER_LAB, FacilityType.CAFETERIA, FacilityType.PRINT_SHOP));
        USEFUL_TYPES.put(LocationType.CAFETERIA, Arrays.asList(FacilityType.RESTROOM, FacilityType.DRINKING_WATER, FacilityType.ATM));
        USEFUL_TYPES.put(LocationType.HOSTEL,    Arrays.asList(FacilityType.DRINKING_WATER, FacilityType.CAFETERIA, FacilityType.FIRST_AID));
        USEFUL_TYPES.put(LocationType.SPORTS,    Arrays.asList(FacilityType.DRINKING_WATER, FacilityType.FIRST_AID, FacilityType.RESTROOM));
        USEFUL_TYPES.put(LocationType.MEDICAL,   Arrays.asList(FacilityType.DRINKING_WATER, FacilityType.RESTROOM, FacilityType.CAFETERIA));
        USEFUL_TYPES.put(LocationType.MAIN_GATE, Arrays.asList(FacilityType.ATM, FacilityType.RESTROOM, FacilityType.DRINKING_WATER));
        USEFUL_TYPES.put(LocationType.BUILDING,  Arrays.asList(FacilityType.RESTROOM, FacilityType.DRINKING_WATER,
                FacilityType.ATM, FacilityType.CAFETERIA, FacilityType.PRINT_SHOP));
        USEFUL_TYPES.put(LocationType.PARKING,   Arrays.asList(FacilityType.RESTROOM, FacilityType.ATM, FacilityType.DRINKING_WATER));
    }

    /** Short reason for each facility type, used in the explanation text. */
    private static final Map<FacilityType, String> REASONS = new EnumMap<>(FacilityType.class);
    static {
        REASONS.put(FacilityType.CAFETERIA, "A good place for a snack or a break.");
        REASONS.put(FacilityType.RESTROOM, "Useful before or after a long visit.");
        REASONS.put(FacilityType.DRINKING_WATER, "Handy for refilling a bottle.");
        REASONS.put(FacilityType.COMPUTER_LAB, "Useful if you need a computer.");
        REASONS.put(FacilityType.PRINT_SHOP, "Useful if you need to print or copy something.");
        REASONS.put(FacilityType.STUDY_AREA, "A quiet place to study.");
        REASONS.put(FacilityType.ATM, "Useful if you need cash.");
        REASONS.put(FacilityType.FIRST_AID, "Good to know in case of an injury or illness.");
    }

    /** One recommended facility with the numbers and the explanation behind it. */
    public static class Recommendation {
        public final Facility facility;
        public final Location location;
        public final double distanceMetres;
        public final boolean atDestination;
        public final boolean onRoute;
        public final double score;
        public final String explanation;

        Recommendation(Facility facility, Location location, double distanceMetres, boolean atDestination,
                       boolean onRoute, double score, String explanation) {
            this.facility = facility;
            this.location = location;
            this.distanceMetres = distanceMetres;
            this.atDestination = atDestination;
            this.onRoute = onRoute;
            this.score = score;
            this.explanation = explanation;
        }
    }

    /** All recommendations for one destination, plus notices about relevant facilities that are unavailable. */
    public static class Result {
        public final Location destination;
        public final List<Recommendation> recommendations;
        public final List<String> notices;

        Result(Location destination, List<Recommendation> recommendations, List<String> notices) {
            this.destination = destination;
            this.recommendations = recommendations;
            this.notices = notices;
        }
    }

    /** Loads the data from MySQL, then applies the rules. sourceId may be null (no route known). */
    public Result recommendFromDatabase(int destinationId, Integer sourceId, boolean requireAccessible) throws SQLException {
        CampusGraph graph = CampusGraph.loadFromDatabase();
        if (!graph.contains(destinationId)) {
            throw new NotFoundException("Destination " + destinationId + " does not exist or is inactive");
        }
        if (sourceId != null && !graph.contains(sourceId)) {
            throw new NotFoundException("Starting location " + sourceId + " does not exist or is inactive");
        }
        return recommend(graph, new FacilityDAO().findAll(), destinationId, sourceId, requireAccessible);
    }

    /** The rules themselves. They need no database, which makes them easy to test. */
    public Result recommend(CampusGraph graph, List<Facility> facilities, int destinationId,
                            Integer sourceId, boolean requireAccessible) {
        Location destination = graph.getLocation(destinationId);
        List<FacilityType> useful = USEFUL_TYPES.get(destination.getType());
        if (useful == null) {
            useful = Collections.emptyList();
        }

        // Walking rules for "how far is it?": no blocked/restricted pathways, step-free if requested, crowds ignored.
        RoutingPreferences walking = RoutingPreferences.defaults()
                .setRequireAccessible(requireAccessible).setCrowdWeight(0);
        ContextAwareRouter router = new ContextAwareRouter();

        // The places the user will pass (so facilities there need no detour).
        Set<Integer> onRoute = new HashSet<>();
        if (sourceId != null) {
            RouteResult route = router.findRoute(graph, sourceId, destinationId,
                    RoutingPreferences.defaults().setRequireAccessible(requireAccessible));
            if (route.isFound()) {
                onRoute.addAll(route.getLocationIds());
            }
        }

        Map<Integer, Double> distanceCache = new HashMap<>();   // location id -> walking metres (null = unreachable)
        Map<FacilityType, Recommendation> bestPerType = new EnumMap<>(FacilityType.class);
        List<String> notices = new ArrayList<>();

        for (Facility f : facilities) {
            int rank = useful.indexOf(f.getType());
            Location where = graph.getLocation(f.getLocationId());
            if (rank < 0 || where == null) {
                continue;                                              // Rule 1, or the location is inactive
            }
            Double distance = walkingDistance(graph, router, walking, distanceCache, destinationId, where.getId());
            if (distance == null) {
                continue;                                              // cannot be reached under the walking rules
            }
            boolean isOnRoute = onRoute.contains(where.getId());
            if (distance > MAX_DISTANCE_METRES && !isOnRoute) {
                continue;                                              // Rule 3: too far
            }
            if (f.getAvailability() != AvailabilityStatus.AVAILABLE) { // Rule 2
                notices.add(f.getName() + " (" + where.getName() + ") is "
                        + f.getAvailability().name().toLowerCase().replace('_', ' ') + ", so it is not recommended.");
                continue;
            }

            double effective = isOnRoute ? 0.0 : distance;
            double score = effective + RANK_WEIGHT * rank;             // Rule 5
            Recommendation candidate = new Recommendation(f, where, distance, where.getId() == destinationId, isOnRoute, score,
                    explain(destination, f, where, distance, isOnRoute));
            Recommendation current = bestPerType.get(f.getType());
            if (current == null || score < current.score
                    || (score == current.score && f.getId() < current.facility.getId())) {
                bestPerType.put(f.getType(), candidate);               // Rule 4
            }
        }

        List<Recommendation> list = new ArrayList<>(bestPerType.values());
        Collections.sort(list, new Comparator<Recommendation>() {
            @Override
            public int compare(Recommendation a, Recommendation b) {
                int byScore = Double.compare(a.score, b.score);
                return byScore != 0 ? byScore : Integer.compare(a.facility.getId(), b.facility.getId());
            }
        });
        if (list.size() > MAX_RESULTS) {
            list = new ArrayList<>(list.subList(0, MAX_RESULTS));
        }
        return new Result(destination, list, notices);
    }

    /** Walking distance in metres from the destination to another location, or null if it cannot be reached. */
    private Double walkingDistance(CampusGraph graph, ContextAwareRouter router, RoutingPreferences walking,
                                   Map<Integer, Double> cache, int destinationId, int locationId) {
        if (locationId == destinationId) {
            return 0.0;
        }
        if (!cache.containsKey(locationId)) {
            RouteResult route = router.findRoute(graph, destinationId, locationId, walking);
            cache.put(locationId, route.isFound() ? Double.valueOf(route.getTotalDistance()) : null);
        }
        return cache.get(locationId);
    }

    private String explain(Location destination, Facility f, Location where, double distance, boolean onRoute) {
        String type = f.getType().name().toLowerCase().replace('_', ' ');
        String place;
        if (where.getId() == destination.getId()) {
            place = "is inside " + destination.getName();
        } else if (onRoute) {
            place = "is at " + where.getName() + ", which is on your route";
        } else {
            place = "is at " + where.getName() + ", about " + Math.round(distance) + " m walk from " + destination.getName();
        }
        return "Rule: visitors to this kind of place often need a " + type + ". " + f.getName() + " " + place + ". "
                + REASONS.get(f.getType());
    }
}
