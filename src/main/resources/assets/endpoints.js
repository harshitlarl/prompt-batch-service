// Admin-port (8081) links can't reuse window.location.origin like the app-port pages do -
// same-origin only gets you the app connector. Point them at the same host on 8081 instead,
// and be upfront about when that's actually reachable (it isn't on the public DO domain,
// which only exposes the app port).
const isLocalHost = ["localhost", "127.0.0.1"].includes(window.location.hostname);
const adminBase = `${window.location.protocol}//${window.location.hostname}:8081`;

document.querySelectorAll(".admin-link").forEach((a) => {
  a.href = adminBase + a.dataset.path;
  a.target = "_blank";
  a.rel = "noopener";
});

const banner = document.getElementById("admin-banner");
if (isLocalHost) {
  banner.className = "banner show success";
  banner.innerHTML =
    `Running locally, so these should be reachable directly at <code>${adminBase}</code>.`;
} else {
  banner.className = "banner show error";
  banner.innerHTML =
    "This deployment only exposes the app port (8080) publicly - admin-port links below " +
    "will time out from here. Reach them by running the service locally " +
    "(<code>docker compose up</code>) or tunneling/exposing port 8081 directly.";
}
