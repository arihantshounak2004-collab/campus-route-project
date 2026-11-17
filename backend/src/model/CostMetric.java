package model;

/**
 * What a shortest-path search should MINIMISE.
 *   DISTANCE - total metres walked
 *   TIME     - total estimated walking seconds
 * The two usually give the same route, but not always (a longer path can be faster).
 */
public enum CostMetric {
    DISTANCE, TIME;

    /** The cost of walking along one pathway under this metric. */
    public double costOf(Pathway pathway) {
        return this == DISTANCE ? pathway.getDistance() : pathway.getEstimatedTime();
    }

    /** Converts text such as "time" to CostMetric.TIME. Throws IllegalArgumentException if unknown. */
    public static CostMetric parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("Cost metric is missing");
        }
        return CostMetric.valueOf(text.trim().toUpperCase());
    }
}
