// NodeNote's service worker: lets the installed app open with no connection.
//
//  - The page, app.js and the small files: network first, the last copy when offline, so an
//    update shows up on the next load as before.
//  - The .wasm files are named by their content, so a cached one is always right: cache first.
//    Copies the current app.js no longer names are dropped, so updates don't pile up.
//  - Anything under /api/ (a server's boards and chats) is never touched.
const CACHE = "nodenote-app-v1";
const WASM = /[0-9a-f]{16,}\.wasm$/;

self.addEventListener("install", () => self.skipWaiting());
self.addEventListener("activate", (event) => event.waitUntil(self.clients.claim()));

self.addEventListener("fetch", (event) => {
  const request = event.request;
  if (request.method !== "GET") return;
  const url = new URL(request.url);
  if (url.origin !== self.location.origin || url.pathname.includes("/api/")) return;
  event.respondWith(WASM.test(url.pathname) ? cacheFirst(request) : networkFirst(request, url));
});

async function cacheFirst(request) {
  const cache = await caches.open(CACHE);
  const hit = await cache.match(request.url);
  if (hit) return hit;
  const response = await fetch(request);
  if (response.ok) await cache.put(request.url, response.clone());
  return response;
}

async function networkFirst(request, url) {
  const cache = await caches.open(CACHE);
  try {
    const response = await fetch(request);
    if (response.ok) {
      await cache.put(request.url, response.clone());
      if (url.pathname.endsWith("/app.js")) dropOldWasm(cache, await response.clone().text());
    }
    return response;
  } catch (offline) {
    const hit = await cache.match(request.url, { ignoreSearch: true })
      || (request.mode === "navigate" ? await cache.match(new URL("./", self.location).href) : undefined);
    if (hit) return hit;
    throw offline;
  }
}

async function dropOldWasm(cache, appJs) {
  const current = new Set(appJs.match(/[0-9a-f]{16,}\.wasm/g) || []);
  for (const key of await cache.keys()) {
    const name = new URL(key.url).pathname.split("/").pop();
    if (WASM.test(name) && !current.has(name)) await cache.delete(key);
  }
}
