package model;

/** Whether a facility can be used right now. */
public enum AvailabilityStatus {
    AVAILABLE, UNAVAILABLE, UNDER_MAINTENANCE;

    public static AvailabilityStatus parse(String text) {
        if (text == null) throw new IllegalArgumentException("Availability status is missing");
        return AvailabilityStatus.valueOf(text.trim().toUpperCase());
    }
}
