package service;

import model.CostMetric;

/**
 * RoutingRequest - what the client is allowed to ask for when it requests a route.
 * Notice what is NOT here: costs, penalties, pathway data and the "restricted paths permitted"
 * switch. Those are decided by the server, never by the client.
 */
public class RoutingRequest {
    public final int sourceId;
    public final int destinationId;
    public final CostMetric metric;
    public final boolean requireAccessible;
    public final double crowdWeight;          // 0 = ignore crowds ... 10 = avoid crowds strongly
    public final boolean clientAskedForRestricted;   // remembered only so the server can say "ignored"

    public RoutingRequest(int sourceId, int destinationId, CostMetric metric, boolean requireAccessible,
                          double crowdWeight, boolean clientAskedForRestricted) {
        this.sourceId = sourceId;
        this.destinationId = destinationId;
        this.metric = metric;
        this.requireAccessible = requireAccessible;
        this.crowdWeight = crowdWeight;
        this.clientAskedForRestricted = clientAskedForRestricted;
    }
}
