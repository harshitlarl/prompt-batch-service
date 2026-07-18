// Shared helpers for the submit + status dashboard pages.
// The pages are served by the same Dropwizard app (AssetsBundle at /ui), so the API
// lives at the same origin/port - no base URL or CORS config needed by default. It's
// still overridable via localStorage for people who want to point the UI at a
// different host (e.g. a remote Docker container).
const API_BASE = localStorage.getItem("pbs_api_base") || window.location.origin;

async function apiFetch(path, options) {
  const res = await fetch(API_BASE + path, options);
  let body = null;
  try {
    body = await res.json();
  } catch (_) {
    // no/invalid JSON body (e.g. 405) - fine, caller only needed the status
  }
  return { ok: res.ok, status: res.status, body };
}

function fmtPercent(p) {
  return (Math.round(p * 10) / 10).toFixed(1) + "%";
}

function fmtTime(value) {
  if (value === null || value === undefined) return "\u2014";
  // Jackson serializes java.time.Instant as epoch seconds (optionally fractional), not an
  // ISO string, so a plain `new Date(value)` (which expects epoch millis or ISO text) would
  // misparse it - convert seconds -> millis first.
  const millis = typeof value === "number" ? value * 1000 : Date.parse(value);
  const d = new Date(millis);
  if (isNaN(d.getTime())) return "\u2014";
  return d.toLocaleString();
}

function escapeHtml(str) {
  return String(str).replace(/[&<>"']/g, (c) => ({
    "&": "&amp;",
    "<": "&lt;",
    ">": "&gt;",
    '"': "&quot;",
    "'": "&#39;",
  })[c]);
}

function statusBadge(status) {
  return `<span class="badge ${status}">${status}</span>`;
}

async function copyToClipboard(text) {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch (_) {
    return false;
  }
}

// Bumps a stat number with a little scale animation whenever its value changes, so live
// updates (polling/auto-refresh) feel alive rather than just silently swapping text.
function setStatValue(el, value) {
  if (!el) return;
  const next = String(value);
  if (el.textContent === next) return;
  el.textContent = next;
  el.classList.remove("bump");
  // Force reflow so the animation replays even if it was just removed.
  void el.offsetWidth;
  el.classList.add("bump");
}

document.addEventListener("DOMContentLoaded", () => {
  document.body.classList.add("ready");
});
