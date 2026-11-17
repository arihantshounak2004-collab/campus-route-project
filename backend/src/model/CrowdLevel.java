package model;

/**
 * How crowded a pathway is. The names match the MySQL ENUM values exactly.
 * (The routing penalty for each level is added in Phase 6, not here.)
 */
public enum CrowdLevel {
    LOW, MEDIUM, HIGH;

    /** Converts text such as "high" to CrowdLevel.HIGH. Throws IllegalArgumentException if unknown. */
    public static CrowdLevel parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("Crowd level is missing");
        }
        return CrowdLevel.valueOf(text.trim().toUpperCase());
    }
}
