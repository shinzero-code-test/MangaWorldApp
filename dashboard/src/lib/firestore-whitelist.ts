/**
 * Single shared whitelist for the super-admin data browser
 * (/api/firestore/*). Prevents the dynamic routes from becoming an
 * arbitrary read/write path into sensitive collections (admin2fa,
 * adminMfaSessions, email_registry, users, ...). Keep in sync with the
 * dashboard data-browser page's COLLECTIONS list.
 */
export const DATA_BROWSER_COLLECTIONS: readonly string[] = [
  "publicProfiles",
  "app_config",
  "community_manga",
  "moderationReports",
  "user_achievements",
  "cloudinaryAssets",
  "releases",
];

const ALLOWED = new Set<string>(DATA_BROWSER_COLLECTIONS);

export function isDataBrowserCollection(collection: unknown): boolean {
  return typeof collection === "string" && ALLOWED.has(collection);
}

/** Document IDs: bounded, no slashes (no path traversal into subcollections). */
export function isValidDocId(docId: unknown): docId is string {
  return typeof docId === "string" && docId.length >= 1 && docId.length <= 512 && !docId.includes("/");
}
