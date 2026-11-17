package model;

/**
 * One place on the campus. In graph terms, a Location is a VERTEX (node).
 * It is a plain data holder: it only stores values, it has no logic.
 * The fields are "final", so once a Location is created it cannot change by accident.
 */
public class Location {

    private final int id;
    private final String name;
    private final LocationType type;
    private final String description;
    private final int x;          // position on the map canvas (0 - 1000)
    private final int y;          // position on the map canvas (0 - 650)
    private final boolean active; // false = hidden/deactivated by the admin

    public Location(int id, String name, LocationType type, String description,
                    int x, int y, boolean active) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.description = description;
        this.x = x;
        this.y = y;
        this.active = active;
    }

    public int getId()               { return id; }
    public String getName()          { return name; }
    public LocationType getType()    { return type; }
    public String getDescription()   { return description; }
    public int getX()                { return x; }
    public int getY()                { return y; }
    public boolean isActive()        { return active; }

    @Override
    public String toString() {
        return "Location{" + id + ", " + name + ", " + type + ", (" + x + "," + y + ")"
                + (active ? "" : ", INACTIVE") + "}";
    }
}
