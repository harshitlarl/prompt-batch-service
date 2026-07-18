document.getElementById("api-base").textContent = API_BASE;

let mode = "text";
let pollTimer = null;

const tabs = document.querySelectorAll(".tab-btn");
const modeText = document.getElementById("mode-text");
const modeJson = document.getElementById("mode-json");
const modeFile = document.getElementById("mode-file");
const promptsText = document.getElementById("prompts-text");
const promptsJson = document.getElementById("prompts-json");
const promptCount = document.getElementById("prompt-count");
const banner = document.getElementById("banner");
const form = document.getElementById("submit-form");
const submitBtn = document.getElementById("submit-btn");
const fileInput = document.getElementById("file-input");
const fileDrop = document.getElementById("file-drop");
const fileDropLabel = document.getElementById("file-drop-label");

let selectedFile = null;

tabs.forEach((btn) => {
  btn.addEventListener("click", () => {
    tabs.forEach((b) => b.classList.remove("active"));
    btn.classList.add("active");
    mode = btn.dataset.mode;
    modeText.style.display = mode === "text" ? "block" : "none";
    modeJson.style.display = mode === "json" ? "block" : "none";
    modeFile.style.display = mode === "file" ? "block" : "none";
    updateCount();
  });
});

function linesFromText(text) {
  return text.split("\n").map((l) => l.trim()).filter((l) => l.length > 0);
}

function updateCount() {
  let n = 0;
  if (mode === "text") {
    n = linesFromText(promptsText.value).length;
  } else if (mode === "json") {
    try {
      const parsed = JSON.parse(promptsJson.value);
      n = Array.isArray(parsed) ? parsed.length : 0;
    } catch (_) {
      n = 0;
    }
  } else if (mode === "file") {
    n = selectedFile ? 1 : 0;
    promptCount.textContent = selectedFile ? `File selected: ${selectedFile.name}` : "0 prompts";
    return;
  }
  promptCount.textContent = `${n} prompt${n === 1 ? "" : "s"}`;
}

promptsText.addEventListener("input", updateCount);
promptsJson.addEventListener("input", updateCount);

fileDrop.addEventListener("click", () => fileInput.click());
fileInput.addEventListener("change", () => {
  selectedFile = fileInput.files[0] || null;
  updateCount();
});
["dragover", "dragleave", "drop"].forEach((evt) => {
  fileDrop.addEventListener(evt, (e) => {
    e.preventDefault();
    fileDrop.classList.toggle("drag", evt === "dragover");
    if (evt === "drop" && e.dataTransfer.files.length) {
      selectedFile = e.dataTransfer.files[0];
      fileInput.files = e.dataTransfer.files;
      updateCount();
    }
  });
});

function showBanner(type, html) {
  banner.className = `banner show ${type}`;
  banner.innerHTML = html;
}

function hideBanner() {
  banner.className = "banner";
}

form.addEventListener("submit", async (e) => {
  e.preventDefault();
  hideBanner();
  submitBtn.disabled = true;
  submitBtn.innerHTML = '<span class="btn-spinner"></span>Submitting\u2026';

  try {
    let result;
    if (mode === "text") {
      const prompts = linesFromText(promptsText.value);
      if (prompts.length === 0) throw new Error("Enter at least one prompt.");
      result = await apiFetch("/batches", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ prompts }),
      });
    } else if (mode === "json") {
      let parsed;
      try {
        parsed = JSON.parse(promptsJson.value);
      } catch (err) {
        throw new Error("Invalid JSON: " + err.message);
      }
      if (!Array.isArray(parsed) || parsed.length === 0) {
        throw new Error("JSON must be a non-empty array of prompt strings.");
      }
      result = await apiFetch("/batches", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ prompts: parsed }),
      });
    } else {
      if (!selectedFile) throw new Error("Choose a file to upload.");
      const text = await selectedFile.text();
      const isJson = selectedFile.name.endsWith(".json");
      result = await apiFetch("/batches/upload", {
        method: "POST",
        headers: { "Content-Type": isJson ? "application/json" : "text/plain" },
        body: text,
      });
    }

    if (result.status === 202 && result.body) {
      showBanner(
        "success",
        `Accepted! Batch <code>${escapeHtml(result.body.batchId)}</code> queued with <strong>${result.body.total}</strong> prompt(s).`
      );
      showResultPanel(result.body.batchId);
    } else {
      const msg = (result.body && (result.body.error || (result.body.errors && result.body.errors.join(", ")) || result.body.message)) || `HTTP ${result.status}`;
      throw new Error(msg);
    }
  } catch (err) {
    showBanner("error", `Submission failed: ${escapeHtml(err.message)}`);
  } finally {
    submitBtn.disabled = false;
    submitBtn.textContent = "Submit Batch";
  }
});

document.getElementById("submit-another").addEventListener("click", () => {
  document.getElementById("result-card").style.display = "none";
  promptsText.value = "";
  promptsJson.value = "";
  selectedFile = null;
  fileInput.value = "";
  updateCount();
  hideBanner();
  clearInterval(pollTimer);
});

function showResultPanel(batchId) {
  const card = document.getElementById("result-card");
  card.style.display = "none";
  // Re-trigger the pop-in animation each time a new batch is submitted.
  card.classList.remove("result-panel");
  void card.offsetWidth;
  card.classList.add("result-panel");
  card.style.display = "block";
  document.getElementById("result-id").textContent = batchId;
  document.getElementById("result-id").onclick = () => copyToClipboard(batchId);

  clearInterval(pollTimer);
  const poll = async () => {
    const { ok, body } = await apiFetch(`/batches/${encodeURIComponent(batchId)}`);
    if (!ok || !body) return;
    document.getElementById("result-badge").innerHTML = statusBadge(body.status);
    document.getElementById("result-fill").style.width = body.percentComplete + "%";
    setStatValue(document.getElementById("stat-total"), body.total);
    setStatValue(document.getElementById("stat-completed"), body.completed);
    setStatValue(document.getElementById("stat-succeeded"), body.succeeded);
    setStatValue(document.getElementById("stat-failed"), body.failed);
    if (body.status === "COMPLETED" || body.status === "FAILED") {
      clearInterval(pollTimer);
    }
  };
  poll();
  pollTimer = setInterval(poll, 1000);
}

updateCount();
