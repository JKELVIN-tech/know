const CACHE = "know-v15";
const ASSETS = [
  "./",
  "./index.html",
  "./login.html",
  "./admin-login.html",
  "./home.html",
  "./books.html",
  "./imports.html",
  "./audits.html",
  "./js/audits.js",
  "./js/imports.js",
  "./news.html",
  "./audit.html",
  "./dashboard.html",
  "./settings.html",
  "./accounts.html",
  "./activity.html",
  "./review.html",
  "./institutions.html",
  "./stock.html",
  "./market.html",
  "./admin-home.html",
  "./privacy.html",
  "./config.js",
  "./css/styles.css",
  "./js/api.js",
  "./js/offline.js",
  "./js/ui.js",
  "./js/home.js",
  "./js/books.js",
  "./js/audit.js",
  "./js/news.js",
  "./js/dashboard.js",
  "./js/settings.js",
  "./js/accounts.js",
  "./js/activity.js",
  "./js/review.js",
  "./js/institutions.js",
  "./js/stock.js",
  "./js/market.js",
  "./js/admin-home.js",
  "./js/privacy.js",
  "./assets/logo.svg",
  "./assets/know-icon-192.png",
  "./assets/know-icon-512.png",
  "./manifest.webmanifest",
];

self.addEventListener("install", (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(ASSETS)).then(() => self.skipWaiting()));
});

self.addEventListener("activate", (e) => {
  e.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener("fetch", (e) => {
  if (e.request.method !== "GET") return;

  // Never intercept the backend API (a different origin — see API_BASE
  // in js/api.js). Let those requests succeed or fail on their own so a
  // real network error isn't masked by a cached HTML page.
  const url = new URL(e.request.url);
  if (url.origin !== self.location.origin) return;

  // Network-first: always prefer the live version so a new deploy takes
  // effect immediately instead of being masked by a stale cache. Cache
  // is only a fallback for when there's no network at all.
  e.respondWith(
    fetch(e.request)
      .then((res) => {
        if (res.ok) {
          const copy = res.clone();
          caches.open(CACHE).then((c) => c.put(e.request, copy));
        }
        return res;
      })
      .catch(() => caches.match(e.request).then((hit) => hit || caches.match("./login.html")))
  );
});
