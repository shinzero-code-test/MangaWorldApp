/**
 * One-off heal for pre-v8.7.1 release builds that pushed Room POJOs directly:
 * R8 renamed the getters, so `users/{uid}/favorites` docs landed with
 * obfuscated single-letter keys (a..g) and no `readingStatus` — invisible to
 * every named-field query (public library) and skipped by every reader.
 *
 * What it does: pages the `favorites` collection group and DELETES docs whose
 * field set contains zero stable keys. Those docs are unreadable by all
 * current code paths (same predicate as the app's junk guard), so deletion
 * changes nothing visible; devices that still own the manga re-push clean
 * rows on the next sync (overwrite semantics).
 *
 * Usage:
 *   FIREBASE_SERVICE_ACCOUNT='<json>' node scripts/heal-favorites.mjs        # dry run
 *   FIREBASE_SERVICE_ACCOUNT='<json>' node scripts/heal-favorites.mjs --live  # delete
 *
 * Auth: Admin SDK (bypasses Firestore rules) — same credential shape as the
 * dashboard server (`FIREBASE_SERVICE_ACCOUNT` or the split env vars).
 */
import { cert, getApps, initializeApp } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";

const KNOWN_KEYS = new Set([
  "mangaId", "slug", "title", "coverUrl", "sourceId", "addedAt",
  "readChapters", "totalChapters", "readingStatus", "isFavorite",
]);

function isJunk(data) {
  const keys = Object.keys(data ?? {});
  return keys.length > 0 && keys.every((k) => !KNOWN_KEYS.has(k));
}

function credentials() {
  const sa = process.env.FIREBASE_SERVICE_ACCOUNT;
  if (sa) {
    const parsed = JSON.parse(sa);
    return { projectId: parsed.project_id, clientEmail: parsed.client_email, privateKey: parsed.private_key };
  }
  return {
    projectId: process.env.FIREBASE_PROJECT_ID ?? "",
    clientEmail: process.env.FIREBASE_CLIENT_EMAIL ?? "",
    privateKey: (process.env.FIREBASE_PRIVATE_KEY ?? "").replace(/\\n/g, "\n"),
  };
}

async function main() {
  const live = process.argv.includes("--live");
  if (getApps().length === 0) initializeApp({ credential: cert(credentials()) });
  const db = getFirestore();

  let scanned = 0;
  let junk = 0;
  let deleted = 0;
  let lastDoc = null;
  const pendingDeletes = [];

  for (;;) {
    let q = db.collectionGroup("favorites").orderBy("__name__").limit(500);
    if (lastDoc) q = q.startAfter(lastDoc);
    const snap = await q.get();
    if (snap.empty) break;
    for (const doc of snap.docs) {
      scanned++;
      if (isJunk(doc.data())) {
        junk++;
        if (live) pendingDeletes.push(doc.ref);
      }
    }
    lastDoc = snap.docs[snap.docs.length - 1];
    if (snap.size < 500) break;
  }

  if (live && pendingDeletes.length > 0) {
    for (let i = 0; i < pendingDeletes.length; i += 400) {
      const batch = db.batch();
      pendingDeletes.slice(i, i + 400).forEach((ref) => batch.delete(ref));
      await batch.commit();
      deleted += Math.min(400, pendingDeletes.length - i);
    }
  }

  console.log(JSON.stringify({ mode: live ? "live" : "dry-run", scanned, junk, deleted }));
  if (!live && junk > 0) console.log("Re-run with --live to delete the junk docs.");
}

main().catch((e) => {
  console.error(`heal-favorites failed: ${e instanceof Error ? e.message : e}`);
  process.exit(1);
});
