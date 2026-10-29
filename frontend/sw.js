const CACHE = "know-v35";
const IMG_CACHE = "know-img-v1";
const IMG_MAX = 150;
const ASSETS = [
  "./",
  "./index.html",
  "./login.html",
  "./admin-login.html",
  "./home.html",
  "./books.html",
  "./imports.html",
  "./audits.html",
  "./partner.html",
  "./partners.html",
  "./js/partner.js",
  "./js/partners.js",
  "./signup.html",
  "./welcome.html",
  "./js/welcome.js",
  "./js/billing-view.js",
  "./js/signup.js",
  "./forgot-password.html",
  "./reset-password.html",
  "./js/password-rules.js",
  "./js/forgot.js",
  "./js/reset.js",
  "./subscription.html",
  "./js/subscription.js",
  "./profit.html",
  "./js/profit.js",
  "./js/boot.js",
  "./js/login-staff.js",
  "./js/login-admin.js",
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
  "./js/receipt.js",
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
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE && k !== IMG_CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener("fetch", (e) => {
  if (e.request.method !== "GET") return;

  // Never intercept the backend API (a different origin — see API_BASE
  // in js/api.js). Let those requests succeed or fail on their own so a
  // real network error isn't masked by a cached HTML page.
  const url = new URL(e.request.url);
  if (url.origin !== self.location.origin) {
    // Pictures from the server (news photos, avatars): show the saved one at once and refresh it quietly
    if (e.request.destination === "image") {
      e.respondWith(
        caches.open(IMG_CACHE).then((cache) => cache.match(e.request).then((hit) => {
          const live = fetch(e.request).then((res) => {
            if (res && (res.ok || res.type === "opaque")) {
              cache.put(e.request, res.clone());
              cache.keys().then((ks) => { if (ks.length > IMG_MAX) cache.delete(ks[0]); });
            }
            return res;
          }).catch(() => hit);
          return hit || live;
        }))
      );
    }
    return;
  }

  // Show the saved copy at once and refresh it in the background; a new deploy appears on the next open.
  e.respondWith(
    caches.match(e.request).then((hit) => {
      const live = fetch(e.request)
        .then((res) => {
          if (res.ok) {
            const copy = res.clone();
            caches.open(CACHE).then((c) => c.put(e.request, copy));
          }
          return res;
        })
        .catch(() => hit || caches.match("./login.html"));
      return hit || live;
    })
  );
});
