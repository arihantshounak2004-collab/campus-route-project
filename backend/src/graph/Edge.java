package graph;

import model.Pathway;

/**
 * One DIRECTION of a pathway, as seen from a location's neighbour list.
 *
 * Example: pathway 7 joins A and B. Then
 *   A's list contains Edge(neighbour = B, pathway 7)
 *   B's list contains Edge(neighbour = A, pathway 7)
 * Both Edge objects point to the SAME Pathway object, so the distance, crowd level
 * and status are stored only once.
 */
public class Edge {

    private final int neighborId;
    private final Pathway pathway;

    public Edge(int neighborId, Pathway pathway) {
        this.neighborId = neighborId;
        this.pathway = pathway;
    }

    /** The id of the location you reach by walking along this edge. */
    public int getNeighborId() { return neighborId; }

    /** The pathway with all its details (distance, time, crowd, status, accessibility). */
    public Pathway getPathway() { return pathway; }

    @Override
    public String toString() {
        return "-> " + neighborId + " via pathway " + pathway.getId();
    }
}
