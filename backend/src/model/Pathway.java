package model;

/**
 * A walkable path between two locations. In graph terms, a Pathway is an EDGE.
 *
 * A pathway is UNDIRECTED: if you can walk A -> B you can also walk B -> A.
 * The database stores it once (source id < destination id) and CampusGraph
 * adds it to both locations' neighbour lists.
 *
 * Units:  distance = metres,  estimatedTime = seconds.
 */
public class Pathway {

    private final int id;
    private final int sourceId;
    private final int destinationId;
    private final double distance;
    private final int estimatedTime;
    private final CrowdLevel crowdLevel;
    private final PathwayStatus status;
    private final boolean accessible;   // true = usable by wheelchair users / no steps

    public Pathway(int id, int sourceId, int destinationId, double distance, int estimatedTime,
                   CrowdLevel crowdLevel, PathwayStatus status, boolean accessible) {
        this.id = id;
        this.sourceId = sourceId;
        this.destinationId = destinationId;
        this.distance = distance;
        this.estimatedTime = estimatedTime;
        this.crowdLevel = crowdLevel;
        this.status = status;
        this.accessible = accessible;
    }

    public int getId()                  { return id; }
    public int getSourceId()            { return sourceId; }
    public int getDestinationId()       { return destinationId; }
    public double getDistance()         { return distance; }
    public int getEstimatedTime()       { return estimatedTime; }
    public CrowdLevel getCrowdLevel()   { return crowdLevel; }
    public PathwayStatus getStatus()    { return status; }
    public boolean isAccessible()       { return accessible; }

    /** Given one end of this pathway, returns the other end. */
    public int otherEnd(int locationId) {
        return locationId == sourceId ? destinationId : sourceId;
    }

    @Override
    public String toString() {
        return "Pathway{" + id + ": " + sourceId + " <-> " + destinationId + ", " + distance + " m, "
                + crowdLevel + ", " + status + (accessible ? "" : ", NOT ACCESSIBLE") + "}";
    }
}
