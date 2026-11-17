// map.js - draws the campus as an SVG: locations = nodes, pathways = lines.
// It never calculates routes. It only draws what the server sends.
"use strict";

const SVG_NS = "http://www.w3.org/2000/svg";

// Each location type gets its own colour and symbol.
const TYPE_STYLE = {
  MAIN_GATE: { color: "#334155", icon: "\u{1F6AA}", label: "Main gate" },
  BUILDING:  { color: "#7c3aed", icon: "\u{1F3E2}", label: "Building" },
  ACADEMIC:  { color: "#2563eb", icon: "\u{1F393}", label: "Academic block" },
  LIBRARY:   { color: "#0d9488", icon: "\u{1F4DA}", label: "Library" },
  CAFETERIA: { color: "#ea580c", icon: "\u{1F37D}", label: "Cafeteria" },
  HOSTEL:    { color: "#db2777", icon: "\u{1F3E0}", label: "Hostel" },
  PARKING:   { color: "#64748b", icon: "P",        label: "Parking" },
  SPORTS:    { color: "#16a34a", icon: "\u26BD",   label: "Sports" },
  MEDICAL:   { color: "#dc2626", icon: "\u271A",   label: "Medical centre" }
};
const ROUTE_COLORS = { shortest: "#2563eb", astar: "#0891b2", contextAware: "#16a34a" };

// Colour/dash of a pathway line. Status wins over crowd.
function pathwayStyle(p) {
  if (p.status === "BLOCKED")    return { stroke: "#dc2626", width: 3, dash: "6 6", label: "Blocked" };
  if (p.status === "RESTRICTED") return { stroke: "#9333ea", width: 3, dash: "2 6", label: "Restricted" };
  if (p.crowdLevel === "HIGH")   return { stroke: "#f59e0b", width: 5, dash: "",    label: "Crowded (high)" };
  if (p.crowdLevel === "MEDIUM") return { stroke: "#fbbf24", width: 3.5, dash: "",  label: "Busy (medium)" };
  return { stroke: "#94a3b8", width: 3, dash: "", label: "Normal" };
}

function svgEl(name, attrs, text) {
  const el = document.createElementNS(SVG_NS, name);
  for (const k in attrs) el.setAttribute(k, attrs[k]);
  if (text !== undefined) el.textContent = text;
  return el;
}

const CampusMap = {
  svg: null, routeLayer: null, nodeEls: {}, onPick: null,

  // Draw the whole map once (or again after data changes).
  render(container, locations, pathways, onPick) {
    this.onPick = onPick;
    this.nodeEls = {};
    container.textContent = "";
    const svg = svgEl("svg", { viewBox: "0 0 1000 650", role: "img", "aria-label": "Campus map" });
    const byId = {};
    locations.forEach(l => { byId[l.id] = l; });

    const edgeLayer = svgEl("g", {});
    pathways.forEach(p => {
      const a = byId[p.sourceId], b = byId[p.destinationId];
      if (!a || !b) return;
      const st = pathwayStyle(p);
      const line = svgEl("line", { x1: a.x, y1: a.y, x2: b.x, y2: b.y, stroke: st.stroke,
                                   "stroke-width": st.width, "stroke-linecap": "round" });
      if (st.dash) line.setAttribute("stroke-dasharray", st.dash);
      line.appendChild(svgEl("title", {}, a.name + " - " + b.name + ": " + p.distance + " m, " + st.label +
                              (p.accessible ? "" : ", not step-free")));
      edgeLayer.appendChild(line);
    });
    svg.appendChild(edgeLayer);

    this.routeLayer = svgEl("g", {});          // the chosen route is drawn on top of the pathways
    svg.appendChild(this.routeLayer);

    const nodeLayer = svgEl("g", {});
    locations.forEach(l => {
      const style = TYPE_STYLE[l.type] || TYPE_STYLE.BUILDING;
      const g = svgEl("g", { class: "node", tabindex: "0", role: "button", "aria-label": l.name });
      g.appendChild(svgEl("circle", { cx: l.x, cy: l.y, r: 20, fill: style.color, stroke: "#fff", "stroke-width": 3 }));
      g.appendChild(svgEl("text", { x: l.x, y: l.y + 6, "text-anchor": "middle", "font-size": 17, fill: "#fff" }, style.icon));
      g.appendChild(svgEl("text", { x: l.x, y: l.y + 40, "text-anchor": "middle", "font-size": 13,
                                    class: "node-label" }, l.name));
      g.appendChild(svgEl("title", {}, l.name + " (" + style.label + ")"));
      const pick = () => { if (this.onPick) this.onPick(l.id); };
      g.addEventListener("click", pick);
      g.addEventListener("keydown", e => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); pick(); } });
      nodeLayer.appendChild(g);
      this.nodeEls[l.id] = g;
    });
    svg.appendChild(nodeLayer);
    container.appendChild(svg);
    this.svg = svg;
  },

  // Highlight the start and the destination.
  markEndpoints(sourceId, destId) {
    for (const id in this.nodeEls) {
      this.nodeEls[id].classList.remove("is-source", "is-dest");
    }
    if (this.nodeEls[sourceId]) this.nodeEls[sourceId].classList.add("is-source");
    if (this.nodeEls[destId]) this.nodeEls[destId].classList.add("is-dest");
  },

  // Draw one route (a list of locations with x/y) as a thick line.
  showRoute(route, color) {
    if (!this.routeLayer) return;
    this.routeLayer.textContent = "";
    if (!route || !route.found || route.locations.length < 2) return;
    const pts = route.locations.map(l => l.x + "," + l.y).join(" ");
    this.routeLayer.appendChild(svgEl("polyline", { points: pts, fill: "none", stroke: "#fff",
                                  "stroke-width": 11, "stroke-linecap": "round", "stroke-linejoin": "round", opacity: 0.9 }));
    this.routeLayer.appendChild(svgEl("polyline", { points: pts, fill: "none", stroke: color,
                                  "stroke-width": 7, "stroke-linecap": "round", "stroke-linejoin": "round" }));
  },

  clearRoute() { if (this.routeLayer) this.routeLayer.textContent = ""; }
};
