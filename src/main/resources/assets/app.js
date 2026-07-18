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

function fmtTime(iso) {
  if (!iso) return "\u2014";
  const d = new Date(iso);
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
