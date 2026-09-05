/* MangaWorld Admin service worker — installability only, no caching.
 *
 * Admin data must never be served stale, so this worker deliberately does
 * NOT cache requests: every fetch passes straight through to the network.
 * Its presence (plus the manifest) is what makes the dashboard installable
 * as a PWA. Bump CACHE_GUARD below if a future version adds real caching —
 * old workers then activate immediately instead of lingering.
 */
const CACHE_GUARD = "mangaworld-admin-v1";

self.addEventListener("install", (event) => {
  self.skipWaiting();
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    (async () => {
      const keys = await caches.keys();
      await Promise.all(keys.filter((k) => k !== CACHE_GUARD).map((k) => caches.delete(k)));
      await self.clients.claim();
    })()
  );
});

self.addEventListener("fetch", () => {
  // Intentional passthrough: no caching of admin traffic.
});
