package model;

/**
 * Whether a pathway can be used.
 *   OPEN       - normal use
 *   RESTRICTED - allowed only for special cases (the context-aware router excludes it by default)
 *   BLOCKED    - closed; no route may ever use it
 */
public enum PathwayStatus {
    OPEN, RESTRICTED, BLOCKED;

    /** Converts text such as "blocked" to PathwayStatus.BLOCKED. Throws IllegalArgumentException if unknown. */
    public static PathwayStatus parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("Pathway status is missing");
        }
        return PathwayStatus.valueOf(text.trim().toUpperCase());
    }
}
