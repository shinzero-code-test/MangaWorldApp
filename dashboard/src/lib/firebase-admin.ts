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

function getApp() {
  const existing = getApps();
  if (existing.length > 0) return existing[0];

  const sa   = process.env.FIREBASE_SERVICE_ACCOUNT;
  const cred: ServiceAccount = sa
    ? (JSON.parse(sa) as ServiceAccount)
    : {
        projectId:   process.env.FIREBASE_PROJECT_ID   ?? "",
        clientEmail: process.env.FIREBASE_CLIENT_EMAIL ?? "",
        privateKey:  (process.env.FIREBASE_PRIVATE_KEY ?? "").replace(/\\n/g, "\n"),
      };

  return initializeApp({
    credential:    cert(cred),
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
  const app = getApp();
  const credential = app.options.credential;
  if (!credential) throw new Error("No Firebase credentials configured");

  // Use the Firebase Admin SDK's internal credential to get an access token
  const { GoogleAuth } = await import("google-auth-library");
  const auth = new GoogleAuth({
    credentials: {
      client_email: (credential as any).serviceAccount?.clientEmail || process.env.FIREBASE_CLIENT_EMAIL,
      private_key: (credential as any).serviceAccount?.privateKey || process.env.FIREBASE_PRIVATE_KEY?.replace(/\\n/g, "\n"),
    },
    scopes: ["https://www.googleapis.com/auth/cloud-platform"],
  });
  const client = await auth.getClient();
  const tokenResponse = await client.getAccessToken();
  return tokenResponse.token || "";
}
