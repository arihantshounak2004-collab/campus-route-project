package model;

/** A facility (restroom, water point, computer lab ...) located inside one campus location. A plain data holder. */
public class Facility {
    private final int id;
    private final String name;
    private final FacilityType type;
    private final int locationId;
    private final String description;
    private final AvailabilityStatus availability;

    public Facility(int id, String name, FacilityType type, int locationId, String description, AvailabilityStatus availability) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.locationId = locationId;
        this.description = description;
        this.availability = availability;
    }

    public int getId()                        { return id; }
    public String getName()                   { return name; }
    public FacilityType getType()             { return type; }
    public int getLocationId()                { return locationId; }
    public String getDescription()            { return description; }
    public AvailabilityStatus getAvailability() { return availability; }

    @Override
    public String toString() {
        return "Facility{" + id + ", " + name + ", " + type + ", location " + locationId + ", " + availability + "}";
    }
}
