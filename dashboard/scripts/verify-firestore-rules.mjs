/**
 * Emulator verification for the public-library Firestore rules surface.
 *
 * Codifies Appendix A of tmp/review/MangaWorldApp_PublicLibrary_Votes_Data_Audit.md:
 * seeds an owner profile (public + private variants), favorites with and
 * without readingStatus, and a legacy profile-less owner — then asserts the
 * app's EXACT visitor query (`whereIn(readingStatus) + orderBy(addedAt desc)
 * + limit(200)`) behaves as intended.
 *
 * Run:  firebase emulators:exec --only firestore "node scripts/verify-firestore-rules.mjs"
 * Requires @firebase/rules-unit-testing (devDependency).
 * Exits non-zero on the first failed assertion (CI gate).
 *
 * NOTE (RA-1): unauthenticated access to the public branches is currently
 * allowed by design-legacy and intentionally NOT asserted here; when RA-1
 * lands (signedIn() on public branches), add the signed-out-denied case.
 */
import { initializeTestEnvironment, assertSucceeds, assertFails } from "@firebase/rules-unit-testing";
import { readFileSync } from "node:fs";
import { doc, setDoc, collection, query, where, orderBy, limit, getDocs } from "firebase/firestore";

const PROJECT_ID = process.env.FIRESTORE_EMULATOR_PROJECT_ID ?? "mangaworld-live-260519";
const STATUSES = ["reading", "completed", "plan_to_read", "on_hold", "dropped"];

const RULES_PATH = new URL("../../firestore.rules", import.meta.url);

function visitorQuery(db, ownerUid) {
  return query(
    collection(doc(db, "users", ownerUid), "favorites"),
    where("readingStatus", "in", STATUSES),
    orderBy("addedAt", "desc"),
    limit(200)
  );
}

async function main() {
  const results = [];
  const check = (name, ok) => {
    results.push({ name, ok });
    console.log(`${ok ? "PASS" : "FAIL"}  ${name}`);
    if (!ok) {
      console.error(JSON.stringify({ failed: name }));
      process.exitCode = 1;
    }
  };

  const testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: { rules: readFileSync(RULES_PATH, "utf8") },
  });

  try {
    // ---- seed as the owner (rules allow owner writes) ----
    // NOTE: capture each context's firestore handle ONCE — every
    // context.firestore() call re-runs useEmulator(), which throws
    // "already been started" once the instance has served requests.
    const owner = testEnv.authenticatedContext("owner-uid");
    const ownerDb = owner.firestore();
    await setDoc(doc(ownerDb, "publicProfiles", "owner-uid"), {
      username: "owner", displayName: "Owner", isPublic: true,
      showListsPublic: true, showActivityPublic: true, showLibraryPublic: true,
    });
    await setDoc(doc(ownerDb, "users", "owner-uid", "favorites", "m1"), {
      mangaId: "m1", slug: "s", title: "T", coverUrl: "", sourceId: "azora",
      addedAt: 2, readChapters: 0, totalChapters: 0, readingStatus: "reading", isFavorite: true,
    });
    await setDoc(doc(ownerDb, "users", "owner-uid", "favorites", "m2"), {
      mangaId: "m2", slug: "s", title: "T", coverUrl: "", sourceId: "azora",
      addedAt: 1, readChapters: 0, totalChapters: 0, readingStatus: null, isFavorite: true,
    });

    // 1. Visitor runs the app's exact query against a public library.
    const visitor = testEnv.authenticatedContext("visitor-uid");
    const visitorDb = visitor.firestore();
    const snap = await assertSucceeds(getDocs(visitorQuery(visitorDb, "owner-uid")));
    check("visitor exact query succeeds", true);
    check("visitor sees only the reading-status doc", snap.docs.length === 1 && snap.docs[0].id === "m1");

    // 2. Owner runs the same query on their own library.
    const ownSnap = await assertSucceeds(getDocs(visitorQuery(ownerDb, "owner-uid")));
    check("owner exact query succeeds with same row", ownSnap.docs.length === 1 && ownSnap.docs[0].id === "m1");

    // 3. Unfiltered list fails closed (cannot prove the status constraint).
    await assertFails(getDocs(query(collection(doc(visitorDb, "users", "owner-uid"), "favorites"), limit(5))));
    check("unfiltered favorites read is denied", true);

    // 4. showLibraryPublic=false denies the visitor.
    await setDoc(doc(ownerDb, "publicProfiles", "owner-uid"), { showLibraryPublic: false }, { merge: true });
    await assertFails(getDocs(visitorQuery(visitorDb, "owner-uid")));
    check("private library denies the visitor", true);
    await setDoc(doc(ownerDb, "publicProfiles", "owner-uid"), { showLibraryPublic: true }, { merge: true });

    // 5. Legacy owner with NO publicProfiles doc: fail-open default.
    const legacy = testEnv.authenticatedContext("legacy-uid");
    const legacyDb = legacy.firestore();
    await setDoc(doc(legacyDb, "users", "legacy-uid", "favorites", "m9"), {
      mangaId: "m9", slug: "s", title: "T", coverUrl: "", sourceId: "azora",
      addedAt: 1, readChapters: 0, totalChapters: 0, readingStatus: "completed", isFavorite: true,
    });
    const legacySnap = await assertSucceeds(getDocs(visitorQuery(visitorDb, "legacy-uid")));
    check("legacy profile-less owner fails open", legacySnap.docs.length === 1 && legacySnap.docs[0].id === "m9");

    // 6. Anonymous sessions cannot write profiles (PL-3 server backstop).
    const anon = testEnv.authenticatedContext("anon-uid", { firebase: { sign_in_provider: "anonymous" } });
    const anonDb = anon.firestore();
    await assertFails(
      setDoc(doc(anonDb, "publicProfiles", "anon-uid"), { username: "ghost", showLibraryPublic: true })
    );
    check("anonymous profile write is denied", true);

    await testEnv.clearFirestore();
  } finally {
    await testEnv.cleanup();
  }

  const failed = results.filter((r) => !r.ok);
  console.log(JSON.stringify({ passed: results.length - failed.length, failed: failed.length }));
  if (failed.length > 0) process.exitCode = 1;
}

main().catch((e) => {
  console.error(`verify-firestore-rules crashed: ${e instanceof Error ? e.message : e}`);
  process.exit(1);
});
