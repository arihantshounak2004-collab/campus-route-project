// api.js - the ONLY place that talks to the Java backend (using the Fetch API).
// Every function returns the decoded JSON, or throws an Error with a readable message.
"use strict";

async function callApi(path, options) {
  let response;
  try {
    response = await fetch(path, options);
  } catch (e) {
    throw new Error("Cannot reach the server. Is it running? (scripts\\run-server.bat)");
  }
  let data = null;
  try { data = await response.json(); } catch (e) { /* body was not JSON */ }
  if (!response.ok) {
    const msg = data && data.error ? data.error : "Request failed (HTTP " + response.status + ")";
    throw new Error(msg);
  }
  return data;
}

const Api = {
  health:     () => callApi("/api/health"),
  summary:    () => callApi("/api/summary"),
  locations:  () => callApi("/api/locations"),
  pathways:   () => callApi("/api/pathways"),
  facilities: (locationId) => callApi("/api/facilities" + (locationId ? "?locationId=" + encodeURIComponent(locationId) : "")),
  recommendations: (destinationId, sourceId, accessible) => callApi("/api/recommendations?destinationId=" +
    encodeURIComponent(destinationId) + (sourceId ? "&sourceId=" + encodeURIComponent(sourceId) : "") +
    "&accessible=" + (accessible ? "true" : "false")),
  compare:    (body) => callApi("/api/routes/compare", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body)
  })
};
