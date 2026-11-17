// app.js - connects the page, the map and the API. All text from the server is inserted with
// textContent (never innerHTML), so data from the database can never run as code.
"use strict";

const state = { locations: [], pathways: [], comparison: null, selected: "contextAware", recs: null };
const $ = id => document.getElementById(id);

// Small helper: h("div", {class: "x"}, ["text", otherElement]) builds an element safely.
function h(tag, attrs, children) {
  const el = document.createElement(tag);
  for (const k in (attrs || {})) el.setAttribute(k, attrs[k]);
  [].concat(children === undefined ? [] : children).forEach(c => {
    el.appendChild(typeof c === "object" && c !== null ? c : document.createTextNode(String(c)));
  });
  return el;
}
const nice = s => s.charAt(0) + s.slice(1).toLowerCase().replace(/_/g, " ");
const ROUTE_NAMES = { shortest: "Shortest (Dijkstra)", astar: "A*", contextAware: "Context-aware" };

// ---------------- messages ----------------
let bannerTimer = null;
function showBanner(text, kind) {
  const b = $("banner");
  b.textContent = text;
  b.className = "banner " + kind;
  clearTimeout(bannerTimer);
  if (kind === "ok") bannerTimer = setTimeout(() => b.classList.add("hidden"), 4000);
}
function hideBanner() { $("banner").classList.add("hidden"); }

// ---------------- navigation ----------------
function showView(name) {
  document.querySelectorAll(".view").forEach(v => v.classList.toggle("hidden", v.id !== "view-" + name));
  document.querySelectorAll(".nav-btn[data-view]").forEach(b => b.classList.toggle("active", b.dataset.view === name));
}

// ---------------- loading the campus ----------------
async function loadCampus() {
  $("statCards").replaceChildren(h("div", { class: "loading" }, "Loading campus data..."));
  try {
    const [health, summary, locations, pathways, facilities] = await Promise.all(
      [Api.health().catch(e => ({ status: "degraded" })), Api.summary(), Api.locations(), Api.pathways(), Api.facilities()]);
    state.locations = locations;
    state.pathways = pathways;
    const ok = health.status === "ok";
    $("serverStatus").textContent = ok ? "Server and database connected" : "Database problem";
    $("serverStatus").className = "server-status " + (ok ? "good" : "bad");
    hideBanner();
    renderDashboard(summary, pathways, facilities);
    fillSelects();
    CampusMap.render($("mapBox"), locations, pathways, onMapPick);
    renderLegend();
  } catch (e) {
    $("serverStatus").textContent = "Not connected";
    $("serverStatus").className = "server-status bad";
    $("statCards").replaceChildren();
    showBanner(e.message + "  (Check that MySQL is running, then reload the page.)", "error");
  }
}

function renderDashboard(summary, pathways, facilities) {
  const cards = [
    ["Locations", summary.locations, ""],
    ["Available pathways", summary.pathways.available, "of " + summary.pathways.total + " total"],
    ["Blocked pathways", summary.pathways.blocked, "cannot be used"],
    ["Restricted pathways", summary.pathways.restricted, "staff only"],
    ["Facilities available", summary.facilities.available, "of " + summary.facilities.total]
  ];
  $("statCards").replaceChildren(...cards.map(c =>
    h("div", { class: "card" }, [h("span", { class: "card-label" }, c[0]), h("strong", {}, c[1]), h("small", {}, c[2])])));

  // crowd bars
  const total = Math.max(1, summary.pathways.total);
  $("crowdBars").replaceChildren(...["LOW", "MEDIUM", "HIGH"].map(level => {
    const n = summary.crowd[level] || 0;
    const fill = h("span", { class: "bar-fill crowd-" + level });
    fill.style.width = Math.round(n * 100 / total) + "%";
    return h("div", { class: "bar-row" }, [h("span", { class: "bar-name" }, nice(level) + " crowd"), h("div", { class: "bar" }, fill), h("span", {}, n)]);
  }));

  // pathways needing attention
  const issues = pathways.filter(p => p.status !== "OPEN" || p.crowdLevel === "HIGH");
  $("attentionList").replaceChildren(...(issues.length === 0
    ? [h("li", { class: "muted" }, "All pathways are open and flowing.")]
    : issues.map(p => h("li", {}, [
        h("span", { class: "badge " + (p.status !== "OPEN" ? "st-" + p.status : "crowd-" + p.crowdLevel) },
          p.status !== "OPEN" ? nice(p.status) : "Crowded"),
        " " + p.sourceName + " \u2013 " + p.destinationName]))));

  // facilities snapshot
  const counts = {};
  facilities.filter(f => f.availability === "AVAILABLE").forEach(f => { counts[f.type] = (counts[f.type] || 0) + 1; });
  const types = Object.keys(counts).sort();
  $("facilitySnapshot").replaceChildren(...(types.length === 0
    ? [h("span", { class: "muted" }, "No facilities are marked available.")]
    : types.map(t => h("span", { class: "chip" }, nice(t) + ": " + counts[t]))));
}

function renderLegend() {
  const items = [];
  const seen = {};
  state.locations.forEach(l => {
    if (seen[l.type]) return;
    seen[l.type] = true;
    const s = TYPE_STYLE[l.type];
    const dot = h("span", { class: "dot" }, s.icon); dot.style.background = s.color;
    items.push(h("span", { class: "legend-item" }, [dot, s.label]));
  });
  const lines = [["#94a3b8", "Normal path"], ["#f59e0b", "Crowded path"], ["#9333ea", "Restricted path"], ["#dc2626", "Blocked path"]];
  lines.forEach(l => {
    const sw = h("span", { class: "swatch" }); sw.style.background = l[0];
    items.push(h("span", { class: "legend-item" }, [sw, l[1]]));
  });
  $("legend").replaceChildren(...items);
}

// ---------------- selects ----------------
function fillSelects() {
  ["qSource", "qDest", "source", "dest"].forEach(id => {
    const sel = $(id);
    const keep = sel.value;
    sel.replaceChildren(h("option", { value: "" }, "Select a location..."),
      ...state.locations.slice().sort((a, b) => a.name.localeCompare(b.name))
        .map(l => h("option", { value: l.id }, l.name)));
    sel.value = keep;
  });
}

function onMapPick(id) {
  const s = $("source"), d = $("dest");
  if (!s.value || (s.value && d.value)) { s.value = id; d.value = ""; }   // start a new pick
  else if (String(id) === s.value) { return; }
  else { d.value = id; }
  CampusMap.markEndpoints(s.value, d.value);
}

// ---------------- route calculation ----------------
async function calculate() {
  const source = $("source").value, dest = $("dest").value;
  if (!source || !dest) { showBanner("Please choose both a start and a destination.", "error"); return; }
  hideBanner();
  $("goBtn").disabled = true;
  $("results").replaceChildren(h("div", { class: "loading" }, "Calculating routes..."));
  CampusMap.markEndpoints(source, dest);
  try {
    const data = await Api.compare({
      sourceId: Number(source), destinationId: Number(dest), metric: $("metric").value,
      requireAccessible: $("accessible").checked, crowdWeight: Number($("crowd").value)
    });
    state.comparison = data;
    state.recs = null;
    const r = data.routes;
    state.selected = r.contextAware.found ? "contextAware" : (r.shortest.found ? "shortest" : "contextAware");
    renderResults();
    showBanner("Routes calculated.", "ok");
    Api.recommendations(dest, source, $("accessible").checked)
      .then(recs => { if (state.comparison === data) { state.recs = recs; renderRecommendations(); } })
      .catch(e => { if (state.comparison === data) { state.recs = { error: e.message }; renderRecommendations(); } });
  } catch (e) {
    $("results").replaceChildren(h("div", { class: "empty" }, "No result to show."));
    showBanner(e.message, "error");
  } finally {
    $("goBtn").disabled = false;
  }
}

function fmtMinutes(seconds) { return (seconds / 60).toFixed(1) + " min"; }

function renderResults() {
  const data = state.comparison, routes = data.routes;
  const unit = data.preferences.penaltyUnit === "second-equivalents" ? "s-eq" : "m-eq";
  const box = $("results");
  const parts = [];

  parts.push(h("h2", {}, data.source.name + " \u2192 " + data.destination.name));
  if (data.warnings && data.warnings.length) parts.push(h("p", { class: "note" }, data.warnings.join(" ")));

  // why this recommendation
  parts.push(h("div", { class: "explain" }, [h("h3", {}, "Recommendation"),
    h("ul", {}, (data.explanation || []).map(t => h("li", {}, t)))]));

  // comparison table
  const keys = ["shortest", "astar", "contextAware"];
  const head = h("tr", {}, [h("th", {}, "")].concat(keys.map(k => h("th", {}, ROUTE_NAMES[k] + (data.recommended === k ? " \u2605" : "")))));
  const row = (label, fn) => h("tr", {}, [h("th", {}, label)].concat(keys.map(k => {
    const r = routes[k];
    return h("td", {}, r.found ? fn(r) : "\u2014");
  })));
  const table = h("table", { class: "compare" }, [
    head,
    row("Distance", r => r.summary.totalDistance.toFixed(1) + " m"),
    row("Walking time", r => fmtMinutes(r.summary.totalTimeSeconds)),
    row("Contextual cost", r => r.evaluation.contextualCost === null ? "n/a" : r.evaluation.contextualCost.toFixed(1) + " " + unit),
    row("Locations passed", r => r.summary.locationCount),
    row("Crowd on route", r => "Low " + r.summary.crowd.LOW + " / Med " + r.summary.crowd.MEDIUM + " / High " + r.summary.crowd.HIGH),
    row("Pathway status", r => "Open " + r.summary.status.OPEN + " / Restricted " + r.summary.status.RESTRICTED + " / Blocked " + r.summary.status.BLOCKED),
    row("Locations examined", r => r.summary.nodesExpanded),
    row("Rules check", r => r.evaluation.recommendable
      ? h("span", { class: "badge ok" }, "Allowed")
      : h("span", { class: "badge st-BLOCKED" }, "Breaks: " + r.evaluation.violations.join("; ")))
  ]);
  parts.push(h("div", { class: "table-wrap" }, table));
  parts.push(h("p", { class: "muted small" }, "Contextual cost = base cost + crowd penalties (" + unit +
    " = " + (unit === "m-eq" ? "metre" : "second") + "-equivalents; illustrative values, not real measurements). " +
    "The three routes are not always different."));

  // tabs and detail
  parts.push(h("div", { class: "tabs" }, keys.map(k => {
    const b = h("button", { class: "tab" + (state.selected === k ? " active" : ""), type: "button" }, ROUTE_NAMES[k]);
    b.addEventListener("click", () => { state.selected = k; renderResults(); });
    return b;
  })));
  parts.push(routeDetail(routes[state.selected]));
  parts.push(h("div", { id: "recs" }));

  box.replaceChildren(...parts);
  CampusMap.showRoute(routes[state.selected], ROUTE_COLORS[state.selected]);
  renderRecommendations();          // redraw (tab changes rebuild the panel)
}

function routeDetail(route) {
  if (!route.found) return h("div", { class: "empty" }, route.message);
  if (route.segments.length === 0) return h("div", { class: "empty" }, route.message);
  const steps = route.segments.map(s => h("li", {}, [
    h("strong", {}, s.fromName + " \u2192 " + s.toName),
    h("span", { class: "muted" }, "  " + s.distance + " m, " + fmtMinutes(s.time) + "  "),
    h("span", { class: "badge crowd-" + s.crowdLevel }, nice(s.crowdLevel) + " crowd"),
    s.status !== "OPEN" ? h("span", { class: "badge st-" + s.status }, nice(s.status)) : "",
    s.accessible ? "" : h("span", { class: "badge st-RESTRICTED" }, "Not step-free")
  ]));
  return h("ol", { class: "steps" }, steps);
}

function renderRecommendations() {
  const holder = $("recs");
  const recs = state.recs;
  if (!holder || !recs) return;
  const name = state.comparison.destination.name;
  if (recs.error) { holder.replaceChildren(h("h3", {}, "Recommended facilities"), h("p", { class: "muted" }, "Could not load recommendations: " + recs.error)); return; }
  const parts = [h("h3", {}, "Recommended facilities near " + name),
    h("p", { class: "muted small" }, "Rule-based suggestions (not machine learning): useful facility types for this kind of place, " +
      "available now, within a " + recs.maxDistanceMetres + " m walk.")];
  if (recs.recommendations.length === 0) parts.push(h("p", { class: "empty" }, "No suitable available facilities were found nearby."));
  parts.push(h("div", { class: "rec-grid" }, recs.recommendations.map(f => {
    const where = f.atDestination ? "At destination" : (f.onRoute ? "On your route" : f.distanceMetres + " m away");
    return h("div", { class: "rec" }, [
      h("div", { class: "rec-head" }, [h("strong", {}, f.name), h("span", { class: "badge " + (f.atDestination || f.onRoute ? "ok" : "") }, where)]),
      h("div", { class: "chips" }, h("span", { class: "chip" }, nice(f.type))),
      h("p", { class: "small" }, f.explanation)]);
  })));
  if (recs.notices.length) parts.push(h("ul", { class: "notices" }, recs.notices.map(n => h("li", {}, n))));
  holder.replaceChildren(...parts);
}

// ---------------- start-up ----------------
document.querySelectorAll(".nav-btn[data-view]").forEach(b => b.addEventListener("click", () => showView(b.dataset.view)));
$("goBtn").addEventListener("click", calculate);
$("swapBtn").addEventListener("click", () => {
  const s = $("source").value; $("source").value = $("dest").value; $("dest").value = s;
  CampusMap.markEndpoints($("source").value, $("dest").value);
});
$("source").addEventListener("change", () => CampusMap.markEndpoints($("source").value, $("dest").value));
$("dest").addEventListener("change", () => CampusMap.markEndpoints($("source").value, $("dest").value));
$("crowd").addEventListener("input", () => { $("crowdOut").textContent = $("crowd").value; });
$("qGo").addEventListener("click", () => {
  if (!$("qSource").value || !$("qDest").value) { showBanner("Please choose both a start and a destination.", "error"); return; }
  $("source").value = $("qSource").value;
  $("dest").value = $("qDest").value;
  showView("planner");
  calculate();
});
loadCampus();
