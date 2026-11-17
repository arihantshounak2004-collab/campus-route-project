package model;

/** The kinds of facility on campus. The names match the MySQL ENUM values exactly. */
public enum FacilityType {
    RESTROOM, DRINKING_WATER, CAFETERIA, COMPUTER_LAB, ATM, PRINT_SHOP, FIRST_AID, STUDY_AREA;

    public static FacilityType parse(String text) {
        if (text == null) throw new IllegalArgumentException("Facility type is missing");
        return FacilityType.valueOf(text.trim().toUpperCase());
    }
}
