let dropId = null;
let totalInventory = 0;
let pollHandle = null;

const apiKeyInput = document.getElementById("apiKey");
apiKeyInput.value = getApiKey();
apiKeyInput.addEventListener("input", () => saveApiKey(apiKeyInput.value.trim()));

function apiKey() {
  return apiKeyInput.value.trim();
}

function setStatus(elId, text, kind) {
  const el = document.getElementById(elId);
  el.textContent = text;
  el.className = "status-line" + (kind ? " " + kind : "");
}

document.getElementById("createDropBtn").addEventListener("click", createDrop);
document.getElementById("claimOneBtn").addEventListener("click", claimOne);
document.getElementById("burstBtn").addEventListener("click", simulateBurst);

async function createDrop() {
  const name = document.getElementById("dropName").value.trim();
  const inventory = parseInt(document.getElementById("dropInventory").value, 10);

  if (!apiKey()) return setStatus("createStatus", "Enter an API key first.", "error");
  if (!name || !inventory || inventory < 1) return setStatus("createStatus", "Enter a name and a positive inventory count.", "error");

  setStatus("createStatus", "Creating…");

  try {
    console.log("API BASE:", API_BASE);
console.log("API KEY:", apiKey());
console.log("REQUEST:", {
  name,
  totalInventory: inventory
});
    const res = await fetch(`${API_BASE}/v1/drops`, {
      method: "POST",
      headers: { "Content-Type": "application/json", "Authorization": `Bearer ${apiKey()}` },
      body: JSON.stringify({ name, totalInventory: inventory })
    });

    if (!res.ok) {
      setStatus("createStatus", `Failed (${res.status}). Check your API key.`, "error");
      return;
    }

    const data = await res.json();
    dropId = data.id;
    totalInventory = data.totalInventory;

    setStatus("createStatus", `Created "${data.name}"`, "ok");
    document.getElementById("emptyState").style.display = "none";
    document.getElementById("dropPanel").style.display = "block";
    document.getElementById("log").innerHTML = '<div class="log-empty">No claims yet — try a manual claim or a burst above.</div>';

    startPolling();
 } catch (err) {
  console.error("CREATE DROP ERROR:", err);
  setStatus("createStatus", `Error: ${err.message}`, "error");
}
}

async function fetchDrop() {
  const res = await fetch(`${API_BASE}/v1/drops/${dropId}`, {
    headers: { "Authorization": `Bearer ${apiKey()}` }
  });
  if (!res.ok) return null;
  return res.json();
}

function startPolling() {
  if (pollHandle) clearInterval(pollHandle);
  refreshCounter();
  pollHandle = setInterval(refreshCounter, 1200);
}

async function refreshCounter() {
  const drop = await fetchDrop();
  if (!drop) return;
  const remaining = drop.remainingInventory;
  const el = document.getElementById("remaining");
  el.textContent = remaining;
  el.classList.toggle("low", remaining <= drop.totalInventory * 0.2);
  document.getElementById("ofTotal").textContent = `of ${drop.totalInventory} remaining`;
}

function appendLog(status) {
  const log = document.getElementById("log");
  if (log.querySelector(".log-empty")) log.innerHTML = "";

  const line = document.createElement("div");
  const ok = status === "SUCCESS";
  line.className = "log-line " + (ok ? "success" : "soldout");
  line.innerHTML = `<span class="dot"></span> ${ok ? "SUCCESS" : "SOLD_OUT"}`;
  log.prepend(line);
}

async function claimOne() {
  try {
    const res = await fetch(`${API_BASE}/v1/drops/${dropId}/claims`, {
      method: "POST",
      headers: { "Content-Type": "application/json", "Authorization": `Bearer ${apiKey()}` },
      body: JSON.stringify({ customerReference: `manual-${Date.now()}` })
    });
    const data = await res.json();
    appendLog(data.status);
    refreshCounter();
  } catch (err) {
    setStatus("burstStatus", "Request failed.", "error");
  }
}

async function simulateBurst() {
  const count = parseInt(document.getElementById("burstCount").value, 10) || 1;
  setStatus("burstStatus", `Firing ${count} concurrent claims…`);

  // All requests are started here, before any await — this is what actually
  // sends them concurrently, rather than one-at-a-time in sequence.
  const requests = [];
  for (let i = 0; i < count; i++) {
    requests.push(
      fetch(`${API_BASE}/v1/drops/${dropId}/claims`, {
        method: "POST",
        headers: { "Content-Type": "application/json", "Authorization": `Bearer ${apiKey()}` },
        body: JSON.stringify({ customerReference: `burst-${Date.now()}-${i}` })
      }).then(r => r.json()).catch(() => ({ status: "SOLD_OUT" }))
    );
  }

  const results = await Promise.all(requests);

  let successCount = 0, soldOutCount = 0;
  results.forEach(r => {
    appendLog(r.status);
    r.status === "SUCCESS" ? successCount++ : soldOutCount++;
  });

  setStatus("burstStatus", `Done — ${successCount} succeeded, ${soldOutCount} rejected.`, "ok");
  refreshCounter();
}