import { initializeApp, getApps, getApp } from "firebase/app";
import { getFirestore, Firestore } from "firebase/firestore";
import { getAuth, Auth } from "firebase/auth";
import { getDatabase, Database } from "firebase/database";

const projectId = process.env.NEXT_PUBLIC_FIREBASE_PROJECT_ID;

if (!projectId) {
  throw new Error("NEXT_PUBLIC_FIREBASE_PROJECT_ID is not configured");
}

// The dashboard is hosted on Vercel, which does not serve Firebase's
// /__/auth/handler route. Popup/redirect sign-in must therefore use a
// Firebase-hosted handler. Prefer an explicitly configured auth domain when
// present (it may be a verified custom domain), otherwise derive the default
// Firebase-hosted handler for this project — never the dashboard origin.
const configuredAuthDomain = (process.env.NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN ?? "").trim();
const authDomain = configuredAuthDomain.length > 0
  ? configuredAuthDomain
  : `${projectId}.firebaseapp.com`;

const firebaseConfig = {
  apiKey: process.env.NEXT_PUBLIC_FIREBASE_CLIENT_API_KEY,
  authDomain,
  projectId,
  storageBucket: process.env.NEXT_PUBLIC_FIREBASE_STORAGE_BUCKET,
  messagingSenderId: process.env.NEXT_PUBLIC_FIREBASE_MESSAGING_SENDER_ID,
  appId: process.env.NEXT_PUBLIC_FIREBASE_APP_ID,
  databaseURL: process.env.NEXT_PUBLIC_FIREBASE_DATABASE_URL,
};

const app = getApps().length === 0 ? initializeApp(firebaseConfig) : getApp();

export const clientDb: Firestore = getFirestore(app);
export const clientAuth: Auth = getAuth(app);
export const clientRtdb: Database = getDatabase(app);
