const tableBody = document.getElementById("table-body");
const emptyState = document.getElementById("empty-state");
const summaryNote = document.getElementById("summary-note");
const filterStatus = document.getElementById("filter-status");
const filterSearch = document.getElementById("filter-search");
const autoRefresh = document.getElementById("auto-refresh");
const liveIndicator = document.getElementById("live-indicator");
const refreshBtn = document.getElementById("refresh-btn");

let refreshTimer = null;
let lastBatches = [];

function applyFilters(batches) {
  const status = filterStatus.value;
  const search = filterSearch.value.trim().toLowerCase();
  return batches.filter((b) => {
    if (status !== "ALL" && b.status !== status) return false;
    if (search && !b.batchId.toLowerCase().includes(search)) return false;
    return true;
  });
}

function render(batches) {
  const filtered = applyFilters(batches);

  if (filtered.length === 0) {
    tableBody.innerHTML = "";
    emptyState.style.display = "block";
    emptyState.querySelector(".hint")?.classList.remove("hidden");
    if (batches.length > 0) {
      emptyState.querySelector("div:nth-child(2)").textContent = "No batches match the current filter.";
    }
  } else {
    emptyState.style.display = "none";
    tableBody.innerHTML = filtered
      .map((b, i) => {
        const created = fmtTime(b.createdAt);
        // Stagger each row's fade-in slightly so a fresh render reads as a cascade rather
        // than everything popping in at once; capped so long lists don't feel sluggish.
        const delay = Math.min(i, 12) * 25;
        return `
        <tr style="animation-delay:${delay}ms">
          <td><span class="mono copy-id" title="Click to copy" data-id="${escapeHtml(b.batchId)}">${escapeHtml(b.batchId)}</span></td>
          <td>${statusBadge(b.status)}</td>
          <td>
            <span class="mini-bar"><span class="fill" style="width:${b.percentComplete}%"></span></span>
            <span class="mono">${fmtPercent(b.percentComplete)} (${b.completed}/${b.total})</span>
          </td>
          <td>${b.total}</td>
          <td style="color:var(--success)">${b.succeeded}</td>
          <td style="color:${b.failed > 0 ? "var(--danger)" : "var(--muted)"}">${b.failed}</td>
          <td class="hint">${created}</td>
          <td class="hint">${fmtTime(b.finishedAt)}</td>
          <td><button class="icon-btn" data-results="${escapeHtml(b.batchId)}">Results</button></td>
        </tr>`;
      })
      .join("");
  }

  const processing = batches.filter((b) => b.status === "PROCESSING").length;
  const completed = batches.filter((b) => b.status === "COMPLETED").length;
  summaryNote.textContent =
    batches.length === 0
      ? ""
      : `${batches.length} batch(es) total — ${processing} processing, ${completed} completed. Showing ${filtered.length}.`;
}

// batchId/createdAt aren't in BatchProgressResponse's shape by default from the list
// endpoint, but createdAt/finishedAt are added by the server for the dashboard - fall
// back gracefully if an older server build doesn't send them yet.
async function loadBatches() {
  const { ok, body } = await apiFetch("/batches");
  if (!ok || !Array.isArray(body)) return;
  lastBatches = body;
  render(lastBatches);
}

tableBody.addEventListener("click", async (e) => {
  const copyTarget = e.target.closest(".copy-id");
  if (copyTarget) {
    await copyToClipboard(copyTarget.dataset.id);
    const prev = copyTarget.textContent;
    copyTarget.textContent = "Copied!";
    setTimeout(() => (copyTarget.textContent = prev), 900);
    return;
  }
  const resultsBtn = e.target.closest("[data-results]");
  if (resultsBtn) {
    const id = resultsBtn.dataset.results;
    const { status, body } = await apiFetch(`/batches/${encodeURIComponent(id)}/results`);
    if (status === 200) {
      alert(`Batch ${id}\n\n${JSON.stringify(body, null, 2)}`.slice(0, 4000));
    } else if (status === 409) {
      alert(`Batch ${id} is still running (${body.percentComplete}% complete).`);
    } else {
      alert(`Could not fetch results (HTTP ${status}).`);
    }
  }
});

filterStatus.addEventListener("change", () => render(lastBatches));
filterSearch.addEventListener("input", () => render(lastBatches));
refreshBtn.addEventListener("click", loadBatches);

function setupAutoRefresh() {
  clearInterval(refreshTimer);
  if (autoRefresh.checked) {
    liveIndicator.style.display = "inline-flex";
    refreshTimer = setInterval(loadBatches, 2000);
  } else {
    liveIndicator.style.display = "none";
  }
}
autoRefresh.addEventListener("change", setupAutoRefresh);

loadBatches();
setupAutoRefresh();
