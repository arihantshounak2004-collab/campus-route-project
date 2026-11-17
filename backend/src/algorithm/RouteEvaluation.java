package algorithm;

import model.CrowdLevel;
import model.Pathway;
import model.PathwayStatus;
import model.RouteResult;
import model.RoutingPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * RouteEvaluation - judges ANY route (from Dijkstra, A* or the context-aware router)
 * against the routing rules, and counts what it contains.
 *
 * Why it exists:
 *  - the comparison screen needs the same facts for all three routes;
 *  - it is the "safety net": a route with any violation of a HARD rule is never recommended;
 *  - the tests use it to double-check the router's output.
 */
public class RouteEvaluation {

    private final boolean found;
    private final double distance;
    private final int time;
    private final int lowCrowdSegments;
    private final int mediumCrowdSegments;
    private final int highCrowdSegments;
    private final int blockedSegments;
    private final int restrictedSegments;
    private final int inaccessibleSegments;
    private final List<String> violations = new ArrayList<>();
    private final double contextualCost;     // NaN when the route breaks a hard rule or does not exist

    public RouteEvaluation(RouteResult route, RoutingPreferences prefs) {
        this.found = route.isFound();
        this.distance = route.getTotalDistance();
        this.time = route.getTotalTime();
        this.lowCrowdSegments = route.countCrowd(CrowdLevel.LOW);
        this.mediumCrowdSegments = route.countCrowd(CrowdLevel.MEDIUM);
        this.highCrowdSegments = route.countCrowd(CrowdLevel.HIGH);
        this.blockedSegments = route.countStatus(PathwayStatus.BLOCKED);
        this.restrictedSegments = route.countStatus(PathwayStatus.RESTRICTED);
        int notAccessible = 0;
        for (Pathway p : route.getPathways()) {
            if (!p.isAccessible()) notAccessible++;
        }
        this.inaccessibleSegments = notAccessible;

        // Which HARD rules does this route break?
        if (found) {
            if (blockedSegments > 0) {
                violations.add("uses " + blockedSegments + " BLOCKED pathway" + plural(blockedSegments));
            }
            if (restrictedSegments > 0 && !prefs.isAllowRestricted()) {
                violations.add("uses " + restrictedSegments + " RESTRICTED pathway" + plural(restrictedSegments)
                        + " (not permitted)");
            }
            if (inaccessibleSegments > 0 && prefs.isRequireAccessible()) {
                violations.add("uses " + inaccessibleSegments + " pathway" + plural(inaccessibleSegments)
                        + " that " + (inaccessibleSegments == 1 ? "is" : "are")
                        + " not accessible (accessibility is required)");
            }
        }

        // The contextual cost only makes sense for a route that obeys the hard rules.
        if (found && violations.isEmpty()) {
            double sum = 0;
            for (Pathway p : route.getPathways()) {
                sum += prefs.segmentCost(p);
            }
            this.contextualCost = Math.round(sum * 10.0) / 10.0;
        } else {
            this.contextualCost = Double.NaN;
        }
    }

    private static String plural(int n) { return n == 1 ? "" : "s"; }

    public boolean isFound()                { return found; }
    public double getDistance()             { return distance; }
    public int getTime()                    { return time; }
    public int getLowCrowdSegments()        { return lowCrowdSegments; }
    public int getMediumCrowdSegments()     { return mediumCrowdSegments; }
    public int getHighCrowdSegments()       { return highCrowdSegments; }
    public int getBlockedSegments()         { return blockedSegments; }
    public int getRestrictedSegments()      { return restrictedSegments; }
    public int getInaccessibleSegments()    { return inaccessibleSegments; }
    public List<String> getViolations()     { return Collections.unmodifiableList(violations); }

    /** True if the route exists and breaks no hard rule. Only such a route may be recommended. */
    public boolean isRecommendable()        { return found && violations.isEmpty(); }

    /** Cost including penalties, or NaN if the route breaks a hard rule (or does not exist). */
    public double getContextualCost()       { return contextualCost; }
    public boolean hasContextualCost()      { return !Double.isNaN(contextualCost); }
}
