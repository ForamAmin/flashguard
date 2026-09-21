// Shared across all three pages: API base config + nav active-state helper.

const API_BASE = "http://localhost:8080";

function markActiveNav() {
  const current = document.body.dataset.page;
  document.querySelectorAll(".nav__links a").forEach(a => {
    if (a.dataset.page === current) a.classList.add("active");
  });
}

document.addEventListener("DOMContentLoaded", markActiveNav);

// Simple persistence so the API key survives navigating between the three pages
// (session-only — cleared when the tab closes; never sent anywhere but this API).
function saveApiKey(key) {
  sessionStorage.setItem("flashguard_api_key", key);
}
function getApiKey() {
  return sessionStorage.getItem("flashguard_api_key") || "";
}