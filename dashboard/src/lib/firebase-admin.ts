import { initializeApp, getApps, cert, type ServiceAccount } from "firebase-admin/app";
import { getAuth,       type Auth }       from "firebase-admin/auth";
import { getFirestore,  type Firestore }  from "firebase-admin/firestore";
import { getStorage,    type Storage }    from "firebase-admin/storage";
import { getMessaging,  type Messaging }  from "firebase-admin/messaging";
import { getRemoteConfig, type RemoteConfig } from "firebase-admin/remote-config";

let _auth:      Auth      | undefined;
let _db:        Firestore | undefined;
let _storage:   Storage   | undefined;
let _messaging: Messaging | undefined;
let _remoteConfig: RemoteConfig | undefined;

function parsedServiceAccount(): { projectId: string; clientEmail: string; privateKey: string } {
  const sa = process.env.FIREBASE_SERVICE_ACCOUNT;
  if (sa) {
    let parsed: Record<string, unknown>;
    try {
      parsed = JSON.parse(sa) as Record<string, unknown>;
    } catch {
      throw new Error("FIREBASE_SERVICE_ACCOUNT is not valid JSON");
    }
    const projectId = typeof parsed.project_id === "string" ? parsed.project_id : "";
    const clientEmail = typeof parsed.client_email === "string" ? parsed.client_email : "";
    const privateKey = typeof parsed.private_key === "string" ? parsed.private_key : "";
    if (!projectId || !clientEmail || !privateKey) {
      throw new Error("FIREBASE_SERVICE_ACCOUNT is missing project_id/client_email/private_key");
    }
    return { projectId, clientEmail, privateKey };
  }
  const projectId = process.env.FIREBASE_PROJECT_ID ?? "";
  const clientEmail = process.env.FIREBASE_CLIENT_EMAIL ?? "";
  const privateKey = (process.env.FIREBASE_PRIVATE_KEY ?? "").replace(/\\n/g, "\n");
  if (!projectId || !clientEmail || !privateKey) {
    throw new Error("Firebase Admin credentials are not configured (FIREBASE_SERVICE_ACCOUNT or FIREBASE_PROJECT_ID/FIREBASE_CLIENT_EMAIL/FIREBASE_PRIVATE_KEY)");
  }
  return { projectId, clientEmail, privateKey };
}

function getApp() {
  const existing = getApps();
  if (existing.length > 0) return existing[0];

  const cred = parsedServiceAccount();

  return initializeApp({
    credential: cert(cred as ServiceAccount),
    storageBucket: process.env.FIREBASE_STORAGE_BUCKET,
  });
}

export function getAdminAuth(): Auth {
  return (_auth ??= getAuth(getApp()));
}
export function getAdminDb(): Firestore {
  return (_db ??= getFirestore(getApp()));
}
export function getAdminStorage(): Storage {
  // Reserved for a future admin storage browser. Cloudinary is currently the
  // ONLY image upload mechanism — do NOT add Firebase Storage uploads without
  // revisiting that architecture decision (see docs; lint forbids the imports).
  return (_storage ??= getStorage(getApp()));
}
export function getAdminMessaging(): Messaging {
  return (_messaging ??= getMessaging(getApp()));
}
export function getAdminRemoteConfig(): RemoteConfig {
  return (_remoteConfig ??= getRemoteConfig(getApp()));
}

/**
 * Get an OAuth2 access token from the Firebase Admin SDK service account.
 * Used for calling Google Cloud REST APIs (Crashlytics, Performance, Analytics).
 */
export async function getAccessToken(): Promise<string> {
  const cred = parsedServiceAccount();
  if (!cred) throw new Error("No Firebase credentials configured");

  // Use explicit credential fields — never the Admin SDK's internal
  // credential shape, which is not a public API.
  const { GoogleAuth } = await import("google-auth-library");
  const auth = new GoogleAuth({
    credentials: {
      client_email: cred.clientEmail,
      private_key: cred.privateKey,
    },
    scopes: ["https://www.googleapis.com/auth/cloud-platform"],
  });
  const client = await auth.getClient();
  const tokenResponse = await client.getAccessToken();
  return tokenResponse.token || "";
}
