package model;

/**
 * RoutingPreferences - every adjustable rule of the context-aware router, in one place.
 *
 * HARD RULES (a route may NEVER break these):
 *   1. BLOCKED pathways are never used.
 *   2. RESTRICTED pathways are not used, UNLESS allowRestricted is true.
 *      allowRestricted is the "permitted-use rule": it must only ever be switched on by
 *      the SERVER for a user who is allowed to use restricted paths (for example an
 *      authenticated administrator or emergency staff). A normal visitor's request can
 *      never switch it on (Phase 7 enforces this).
 *   3. If requireAccessible is true, pathways that are not accessible (steps, steep slope)
 *      are never used.
 *
 * SOFT RULES (they only make a route more "expensive", the route stays allowed):
 *   - Crowd penalty per pathway segment:   LOW 0,  MEDIUM 3,  HIGH 8   (defaults)
 *   - Extra penalty for a permitted RESTRICTED segment: 15            (default)
 *
 * UNITS: a penalty is measured in the SAME unit as the cost being minimised:
 *   - metric DISTANCE: "metre-equivalents"  (a penalty of 8 feels like 8 extra metres of walking)
 *   - metric TIME:     "second-equivalents" (a penalty of 8 feels like 8 extra seconds)
 *   The values 0 / 3 / 8 / 15 are ILLUSTRATIVE choices for this project. They are NOT measured
 *   real-world values. Change them with the setters below, or use crowdWeight to scale all
 *   crowd penalties at once (crowdWeight = 5 means "I really dislike crowds").
 *
 * Total cost of walking one pathway =
 *      base cost (distance or time)
 *    + crowdWeight x crowdPenalty(crowd level)
 *    + restrictedPenalty (only if the pathway is RESTRICTED and allowed)
 */
public class RoutingPreferences {

    public static final double DEFAULT_LOW_PENALTY = 0;
    public static final double DEFAULT_MEDIUM_PENALTY = 3;
    public static final double DEFAULT_HIGH_PENALTY = 8;
    public static final double DEFAULT_RESTRICTED_PENALTY = 15;

    private CostMetric metric = CostMetric.DISTANCE;
    private boolean requireAccessible = false;
    private boolean allowRestricted = false;
    private double lowPenalty = DEFAULT_LOW_PENALTY;
    private double mediumPenalty = DEFAULT_MEDIUM_PENALTY;
    private double highPenalty = DEFAULT_HIGH_PENALTY;
    private double crowdWeight = 1.0;
    private double restrictedPenalty = DEFAULT_RESTRICTED_PENALTY;

    /** Standard settings: minimise distance, no accessibility requirement, restricted paths forbidden. */
    public static RoutingPreferences defaults() {
        return new RoutingPreferences();
    }

    // ---------------- setters (each returns "this" so calls can be chained) ----------------

    public RoutingPreferences setMetric(CostMetric metric) {
        if (metric == null) throw new IllegalArgumentException("metric is required");
        this.metric = metric;
        return this;
    }

    public RoutingPreferences setRequireAccessible(boolean value) {
        this.requireAccessible = value;
        return this;
    }

    public RoutingPreferences setAllowRestricted(boolean value) {
        this.allowRestricted = value;
        return this;
    }

    /** Sets the penalties for LOW, MEDIUM and HIGH crowds. Each must be 0 or more, and LOW <= MEDIUM <= HIGH. */
    public RoutingPreferences setCrowdPenalties(double low, double medium, double high) {
        requireNonNegative(low, "LOW penalty");
        requireNonNegative(medium, "MEDIUM penalty");
        requireNonNegative(high, "HIGH penalty");
        if (low > medium || medium > high) {
            throw new IllegalArgumentException("Penalties must not decrease: LOW <= MEDIUM <= HIGH");
        }
        this.lowPenalty = low;
        this.mediumPenalty = medium;
        this.highPenalty = high;
        return this;
    }

    /** Multiplies all crowd penalties. 1 = normal, 0 = ignore crowds, 5 = strongly avoid crowds. */
    public RoutingPreferences setCrowdWeight(double weight) {
        requireNonNegative(weight, "crowd weight");
        this.crowdWeight = weight;
        return this;
    }

    public RoutingPreferences setRestrictedPenalty(double penalty) {
        requireNonNegative(penalty, "restricted penalty");
        this.restrictedPenalty = penalty;
        return this;
    }

    private static void requireNonNegative(double value, String what) {
        // "value >= 0" is false for NaN too, so NaN is rejected as well.
        if (!(value >= 0) || Double.isInfinite(value)) {
            throw new IllegalArgumentException(what + " must be a finite number, 0 or more");
        }
    }

    // ---------------- getters ----------------

    public CostMetric getMetric()         { return metric; }
    public boolean isRequireAccessible()  { return requireAccessible; }
    public boolean isAllowRestricted()    { return allowRestricted; }
    public double getCrowdWeight()        { return crowdWeight; }
    public double getRestrictedPenalty()  { return restrictedPenalty; }

    // ---------------- the rules, as methods ----------------

    /** The penalty for walking a pathway with this crowd level (already multiplied by the crowd weight). */
    public double crowdPenalty(CrowdLevel level) {
        double raw;
        switch (level) {
            case LOW:    raw = lowPenalty;    break;
            case MEDIUM: raw = mediumPenalty; break;
            default:     raw = highPenalty;   break;     // HIGH
        }
        return crowdWeight * raw;
    }

    /** True if the HARD rules allow this pathway to be used at all. */
    public boolean isUsable(Pathway p) {
        if (p.getStatus() == PathwayStatus.BLOCKED) {
            return false;                                   // rule 1: never
        }
        if (p.getStatus() == PathwayStatus.RESTRICTED && !allowRestricted) {
            return false;                                   // rule 2: only with permission
        }
        if (requireAccessible && !p.isAccessible()) {
            return false;                                   // rule 3: accessibility
        }
        return true;
    }

    /**
     * The cost of walking one pathway, or POSITIVE_INFINITY if a hard rule forbids it.
     * (An infinite cost can never be part of a cheapest route, so it works as "do not use".)
     */
    public double segmentCost(Pathway p) {
        if (!isUsable(p)) {
            return Double.POSITIVE_INFINITY;
        }
        double cost = metric.costOf(p) + crowdPenalty(p.getCrowdLevel());
        if (p.getStatus() == PathwayStatus.RESTRICTED) {
            cost += restrictedPenalty;
        }
        return cost;
    }
}
