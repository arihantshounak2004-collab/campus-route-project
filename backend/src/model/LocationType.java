package model;

/** The kind of place a location is. The map uses it to choose a colour and icon. */
public enum LocationType {
    MAIN_GATE, BUILDING, ACADEMIC, LIBRARY, CAFETERIA, HOSTEL, PARKING, SPORTS, MEDICAL;

    /** Converts text such as "library" to LocationType.LIBRARY. Throws IllegalArgumentException if unknown. */
    public static LocationType parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("Location type is missing");
        }
        return LocationType.valueOf(text.trim().toUpperCase());
    }
}
