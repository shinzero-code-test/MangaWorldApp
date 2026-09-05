"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { AlertCircle, Eye, EyeOff, Loader2 } from "lucide-react";
import {
  GoogleAuthProvider,
  getRedirectResult,
  signInWithCustomToken,
  signInWithEmailAndPassword,
  signInWithRedirect,
} from "firebase/auth";
import { clientAuth } from "@/lib/firebase-client";
import { useEffect, useRef } from "react";

// Google Identity Services button (primary Google flow — works where the
// firebaseapp.com iframe relay used by popup/redirect is dead).
const GIS_SCRIPT_SRC = "https://accounts.google.com/gsi/client";
const GIS_CLIENT_ID = (process.env.NEXT_PUBLIC_GOOGLE_CLIENT_ID ?? "").trim();

interface GisCredentialResponse {
  credential?: string;
  select_by?: string;
}
interface GisIdClient {
  initialize: (options: Record<string, unknown>) => void;
  renderButton: (element: HTMLElement, options: Record<string, unknown>) => void;
}
declare global {
  interface Window {
    google?: { accounts?: { id?: GisIdClient } };
  }
}

function authErrorCode(error: unknown): string | undefined {
  if (typeof error !== "object" || error === null || !("code" in error)) return undefined;
  return typeof error.code === "string" ? error.code : undefined;
}

function googleSignInErrorMessage(error: unknown): string {
  const code = authErrorCode(error);
  const messages: Record<string, string> = {
    "auth/popup-closed-by-user": "تم إغلاق نافذة تسجيل الدخول",
    "auth/cancelled-popup-request": "تم إلغاء طلب تسجيل الدخول",
    "auth/popup-blocked": "تم حظر النافذة المنبثقة — جارٍ التحويل لتسجيل الدخول عبر التحويل",
    "auth/unauthorized-domain": "نطاق لوحة التحكم غير مصرّح به في Firebase (Authorized domains). أضف نطاق Vercel في Firebase Console ← Authentication ← Settings.",
    "auth/operation-not-allowed": "تسجيل الدخول بـ Google غير مفعّل في Firebase Console.",
    "auth/operation-not-supported-in-this-environment": "المتصفح لا يدعم النوافذ المنبثقة — جارٍ التحويل لتسجيل الدخول عبر التحويل",
    "auth/network-request-failed": "تعذر الاتصال بـ Google. تحقق من الإنترنت وحاول مجدداً.",
    "auth/account-exists-with-different-credential": "هذا البريد مسجّل بطريقة أخرى — سجّل الدخول بالبريد وكلمة المرور أولاً.",
    "auth/user-disabled": "تم تعطيل هذا الحساب. تواصل مع مدير النظام.",
    "auth/web-storage-unsupported": "المتصفح يمنع التخزين المحلي (ملفات تعريف الارتباط/التخزين) — فعّله أو جرّب متصفحاً آخر.",
    "auth/popup-timeout": "انتهت مهلة النافذة المنبثقة — جارٍ التحويل لتسجيل الدخول عبر التحويل",
  };
  return (code && messages[code]) || "تعذر بدء تسجيل الدخول بـ Google. حاول مرة أخرى.";
}

function googleSignInDetails(stage: string, error: unknown): string {
  // Raw diagnostic line (never translated): stage-tagged so a report names the
  // exact failing leg — popup, redirect-start, redirect-return, or session —
  // instead of "it doesn't work".
  if (typeof error !== "object" || error === null) return `[${stage}]`;
  const code = "code" in error && typeof error.code === "string" ? error.code : "";
  const message = "message" in error && typeof error.message === "string" ? error.message : "";
  return [`[${stage}]`, code, message].filter(Boolean).join(" — ");
}

// sessionStorage marker so the return leg knows a Google redirect was
// initiated by us (vs. a plain visit to /login). Per-tab by design: a second
// tab never mistakes itself for the returning one.
const REDIRECT_MARKER = "mw_google_redirect";

// Redirect is the ONLY Google flow (no popup): popups hang forever in
// storage-walled browsers — chooser completes, popup closes, but the auth
// event never reaches the opener, so no session POST is ever sent and the
// user lands back with no error. Redirect delivers the result via URL params
// plus first-party storage and every failure mode is visible.
async function startGoogleRedirect(): Promise<never> {
  const provider = new GoogleAuthProvider();
  provider.setCustomParameters({ prompt: "select_account" });
  if (typeof sessionStorage !== "undefined") sessionStorage.setItem(REDIRECT_MARKER, "1");
  await signInWithRedirect(clientAuth, provider);
  // signInWithRedirect navigates away; if it ever returns, treat as failure.
  throw { code: "auth/internal-error", message: "redirect did not navigate" };
}

function emailSignInErrorMessage(error: unknown): string {
  const messages: Record<string, string> = {
    "auth/user-not-found": "البريد الإلكتروني غير مسجل",
    "auth/wrong-password": "كلمة المرور غير صحيحة",
    "auth/invalid-credential": "بيانات تسجيل الدخول غير صحيحة",
    "auth/too-many-requests": "تم تقييد الحساب مؤقتاً. حاول لاحقاً",
    "auth/invalid-email": "البريد الإلكتروني غير صالح",
  };
  return messages[authErrorCode(error) ?? ""] ?? "خطأ في تسجيل الدخول";
}

export default function LoginPage() {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [showPw, setShowPw] = useState(false);
  const [error, setError] = useState("");
  const [googleDetails, setGoogleDetails] = useState("");
  const [loading, setLoading] = useState(false);
  const [googleLoading, setGoogleLoading] = useState(false);
  const [gisReady, setGisReady] = useState(false);
  const [gisFailed, setGisFailed] = useState(false);
  const gisButtonRef = useRef<HTMLDivElement | null>(null);
  const router = useRouter();

  // Caps the claims-refresh handshake: a persistently true `refreshRequired` (stale custom
  // claims, clock skew) must never loop forever hammering forced token refreshes.
  const MAX_REFRESH_ATTEMPTS = 2;

  const handleSession = async (idToken: string, refreshToken: () => Promise<string>, attempt = 0) => {
    const res = await fetch("/api/auth/google", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ idToken }),
    });
    const data = await res.json();
    if (!res.ok) {
      const suffix = typeof data?.email === "string" && data.email ? ` (${data.email})` : "";
      throw new Error((data.error || "خطأ في تسجيل الدخول") + suffix);
    }
    if (data.refreshRequired) {
      if (attempt >= MAX_REFRESH_ATTEMPTS) {
        throw new Error("تعذر تحديث صلاحيات الحساب. حاول تسجيل الدخول مرة أخرى.");
      }
      await handleSession(await refreshToken(), refreshToken, attempt + 1);
      return;
    }
    router.push("/2fa");
    router.refresh();
  };

  const handleGoogle = async () => {
    setGoogleLoading(true);
    setError("");
    setGoogleDetails("");
    try {
      // Redirect fallback (only used when the GIS button cannot load):
      // navigates to Google and back. The mount effect below completes the
      // flow on return.
      await startGoogleRedirect();
      return;
    } catch (error: unknown) {
      if (typeof sessionStorage !== "undefined") sessionStorage.removeItem(REDIRECT_MARKER);
      setError(googleSignInErrorMessage(error));
      setGoogleDetails(googleSignInDetails("redirect-start", error));
      console.error("[login] google redirect failed:", error);
    } finally {
      setGoogleLoading(false);
    }
  };

  // GIS credential → Firebase custom token → normal session pipeline.
  // signInWithCustomToken is a direct Identity Toolkit call: no firebaseapp
  // iframe involved, so it works where popup/redirect find no result.
  const handleGisCredential = async (credential: string) => {
    setGoogleLoading(true);
    setError("");
    setGoogleDetails("");
    try {
      const res = await fetch("/api/auth/google-credential", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ credential }),
      });
      const data = await res.json();
      if (!res.ok) {
        throw new Error(data.error || "تعذر تسجيل الدخول بـ Google");
      }
      if (typeof data?.customToken !== "string" || !data.customToken) {
        throw new Error("تعذر تسجيل الدخول بـ Google");
      }
      await signInWithCustomToken(clientAuth, data.customToken);
      const user = clientAuth.currentUser;
      if (!user) throw new Error("تعذر تسجيل الدخول بـ Google");
      await handleSession(await user.getIdToken(), () => user.getIdToken(true));
    } catch (error: unknown) {
      setError(error instanceof Error ? error.message : "تعذر تسجيل الدخول بـ Google");
      setGoogleDetails(googleSignInDetails("gis", error instanceof Error ? { code: "", message: error.message } : error));
      console.error("[login] gis sign-in failed:", error);
    } finally {
      setGoogleLoading(false);
    }
  };

  // Loads the GIS button. If the script cannot load (or no client ID is
  // configured), the legacy redirect button below takes over.
  useEffect(() => {
    if (!GIS_CLIENT_ID) {
      setGisFailed(true);
      return;
    }
    let cancelled = false;
    const init = () => {
      if (cancelled) return;
      try {
        const gis = window.google?.accounts?.id;
        if (!gis) throw new Error("gis unavailable");
        gis.initialize({
          client_id: GIS_CLIENT_ID,
          callback: (response: unknown) => {
            const credential = typeof response === "object" && response !== null && "credential" in response
              ? (response as GisCredentialResponse).credential
              : undefined;
            if (typeof credential === "string" && credential) void handleGisCredential(credential);
          },
          auto_select: false,
          itp_support: true,
        });
        if (gisButtonRef.current) {
          gis.renderButton(gisButtonRef.current, {
            type: "standard",
            theme: "filled_black",
            size: "large",
            text: "signin_with",
            shape: "pill",
            logo_alignment: "left",
          });
          setGisReady(true);
        } else {
          throw new Error("gis container missing");
        }
      } catch (error: unknown) {
        console.error("[login] gis init failed:", error);
        if (!cancelled) setGisFailed(true);
      }
    };
    if (document.querySelector(`script[src="${GIS_SCRIPT_SRC}"]`)) {
      init();
      return () => { cancelled = true; };
    }
    const script = document.createElement("script");
    script.src = GIS_SCRIPT_SRC;
    script.async = true;
    script.defer = true;
    script.onload = init;
    script.onerror = () => { if (!cancelled) setGisFailed(true); };
    document.head.appendChild(script);
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Completes the redirect flow started by handleGoogle (redirect-first).
  // The marker distinguishes a genuine redirect return from a plain page load.
  // On auth/internal-error the result is retried once: the handler sometimes
  // needs a beat to settle its stored state after navigation.
  useEffect(() => {
    let cancelled = false;
    (async () => {
      let result = null;
      try {
        result = await getRedirectResult(clientAuth);
      } catch (error: unknown) {
        const returning = typeof sessionStorage !== "undefined"
          && sessionStorage.getItem(REDIRECT_MARKER) === "1";
        if (!cancelled && returning && authErrorCode(error) === "auth/internal-error") {
          await new Promise((r) => setTimeout(r, 600));
          if (cancelled) return;
          try {
            result = await getRedirectResult(clientAuth);
          } catch (retryError: unknown) {
            if (!cancelled) {
              setError(googleSignInErrorMessage(retryError));
              setGoogleDetails(googleSignInDetails("redirect-return", retryError));
              console.error("[login] google redirect result failed (retry):", retryError);
            }
            if (typeof sessionStorage !== "undefined") sessionStorage.removeItem(REDIRECT_MARKER);
            if (!cancelled) setGoogleLoading(false);
            return;
          }
        } else {
          if (!cancelled && returning) {
            setError(googleSignInErrorMessage(error));
            setGoogleDetails(googleSignInDetails("redirect-return", error));
            console.error("[login] google redirect result failed:", error);
          }
          if (typeof sessionStorage !== "undefined") sessionStorage.removeItem(REDIRECT_MARKER);
          if (!cancelled) setGoogleLoading(false);
          return;
        }
      }
      try {
        if (!result || cancelled) {
          // A returning redirect with NO result is the previously-silent
          // dead end (no error, no session POST, user just lands on /login).
          // Name it loudly: storage cleared mid-flight, handoff started in
          // another tab, or the browser dropped the redirect state.
          const returning = typeof sessionStorage !== "undefined"
            && sessionStorage.getItem(REDIRECT_MARKER) === "1";
          if (!cancelled && returning && !result) {
            setError("اكتمل التحويل من Google لكن لم يتم العثور على نتيجة تسجيل الدخول. حاول مرة أخرى، أو جرّب متصفحاً آخر.");
            setGoogleDetails("[redirect-return] auth/no-pending-result");
            console.error("[login] google redirect returned no result (marker present)");
          }
          if (typeof sessionStorage !== "undefined") sessionStorage.removeItem(REDIRECT_MARKER);
          if (!cancelled) setGoogleLoading(false);
          return;
        }
        if (typeof sessionStorage !== "undefined") sessionStorage.removeItem(REDIRECT_MARKER);
        setGoogleLoading(true);
        await handleSession(await result.user.getIdToken(), () => result.user.getIdToken(true));
      } catch (error: unknown) {
        if (!cancelled) {
          setError(error instanceof Error ? error.message : googleSignInErrorMessage(error));
          if (!(error instanceof Error)) setGoogleDetails(googleSignInDetails("redirect-return", error));
          else setGoogleDetails(`[session] ${error.message}`.slice(0, 300));
          console.error("[login] google redirect session failed:", error);
        }
      } finally {
        if (!cancelled) setGoogleLoading(false);
      }
    })();
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const handleEmail = async (ev: React.FormEvent) => {
    ev.preventDefault();
    setLoading(true);
    setError("");
    setGoogleDetails("");
    try {
      const result = await signInWithEmailAndPassword(clientAuth, email, password);
      await handleSession(await result.user.getIdToken(), () => result.user.getIdToken(true));
    } catch (error: unknown) {
      setError(emailSignInErrorMessage(error));
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="min-h-screen flex" dir="rtl">
      {/* ── Left decorative panel ── */}
      <div
        className="hidden lg:flex lg:w-[60%] relative overflow-hidden flex-col items-center justify-center p-12"
        style={{ background: "#0a0812" }}
      >
        {/* Gradient mesh background */}
        <div
          className="absolute inset-0"
          style={{
            background: `
              radial-gradient(ellipse 80% 60% at 20% 30%, rgba(139,92,246,0.25) 0%, transparent 70%),
              radial-gradient(ellipse 60% 80% at 80% 70%, rgba(124,58,237,0.15) 0%, transparent 70%),
              radial-gradient(ellipse 40% 40% at 50% 50%, rgba(167,139,250,0.08) 0%, transparent 70%)
            `,
          }}
        />
        {/* Grid pattern */}
        <div
          className="absolute inset-0 opacity-[0.04]"
          style={{
            backgroundImage: `
              linear-gradient(rgba(139,92,246,0.8) 1px, transparent 1px),
              linear-gradient(90deg, rgba(139,92,246,0.8) 1px, transparent 1px)
            `,
            backgroundSize: "40px 40px",
          }}
        />

        {/* Content */}
        <div className="relative text-center z-10 space-y-6">
          <div
            className="w-20 h-20 rounded-3xl flex items-center justify-center mx-auto shadow-2xl"
            style={{
              background: "linear-gradient(135deg, #7c3aed, #4c1d95)",
              boxShadow: "0 20px 60px rgba(124,58,237,0.4)",
            }}
          >
            <img src="/logo.png" alt="Logo" className="w-12 h-12 object-contain" />
          </div>
          <div>
            <h1 className="text-4xl font-bold text-white tracking-tight">
              MangaWorld
            </h1>
            <p
              className="text-lg mt-2"
              style={{ color: "rgba(167,139,250,0.9)" }}
            >
              لوحة تحكم مانجا وورلد
            </p>
          </div>
          <p
            className="text-sm max-w-xs mx-auto leading-relaxed"
            style={{ color: "rgba(167,139,250,0.5)" }}
          >
            منصة إدارة شاملة لتطبيق مانجا وورلد. تحكم في المستخدمين، المحتوى،
            والتحليلات من مكان واحد.
          </p>

          {/* Feature highlights */}
          <div className="flex gap-4 justify-center mt-8 flex-wrap">
            {[
              { icon: "👥", label: "إدارة المستخدمين" },
              { icon: "📊", label: "تحليلات مباشرة" },
              { icon: "🛡️", label: "مراقبة المحتوى" },
            ].map((s) => (
              <div
                key={s.label}
                className="px-4 py-3 rounded-xl text-center"
                style={{
                  background: "rgba(139,92,246,0.12)",
                  border: "1px solid rgba(139,92,246,0.2)",
                }}
              >
                <p className="text-xl">{s.icon}</p>
                <p className="text-xs mt-0.5" style={{ color: "rgba(167,139,250,0.7)" }}>
                  {s.label}
                </p>
              </div>
            ))}
          </div>
        </div>
      </div>

      {/* ── Right form panel ── */}
      <div
        className="flex-1 flex items-center justify-center p-6"
        style={{ background: "var(--background)" }}
      >
        <div className="w-full max-w-sm space-y-6">
          {/* Mobile logo */}
          <div className="lg:hidden flex items-center gap-3 justify-center">
            <div
              className="w-10 h-10 rounded-xl flex items-center justify-center"
              style={{ background: "var(--primary)" }}
            >
              <img src="/logo.png" alt="Logo" className="w-6 h-6 object-contain" />
            </div>
            <div>
              <p className="font-bold">MangaWorld</p>
              <p className="text-xs" style={{ color: "var(--muted-foreground)" }}>
                لوحة التحكم
              </p>
            </div>
          </div>

          <div>
            <h2 className="text-2xl font-bold">تسجيل الدخول</h2>
            <p className="text-sm mt-1" style={{ color: "var(--muted-foreground)" }}>
              أدخل بياناتك للوصول إلى لوحة التحكم
            </p>
          </div>

          {/* Google button: official GIS button (primary — no firebaseapp iframe).
              Falls back to the redirect flow only if GIS cannot load. */}
          {!gisFailed && (
            <div className="w-full" dir="ltr">
              <div ref={gisButtonRef} className="w-full flex justify-center" />
              {!gisReady && (
                <div
                  className="w-full flex items-center justify-center gap-3 py-3 rounded-xl border font-medium text-sm"
                  style={{
                    borderColor: "var(--border)",
                    color: "var(--muted-foreground)",
                    background: "var(--card)",
                  }}
                  aria-hidden="true"
                >
                  <Loader2 size={18} className="animate-spin" />
                  <span>جاري تحميل زر Google...</span>
                </div>
              )}
            </div>
          )}
          {gisFailed && (
          <button
            onClick={handleGoogle}
            disabled={googleLoading || loading}
            className="w-full flex items-center justify-center gap-3 py-3 rounded-xl border font-medium text-sm transition hover:bg-[var(--accent)] disabled:opacity-50 disabled:cursor-not-allowed"
            style={{
              borderColor: "var(--border)",
              color: "var(--foreground)",
              background: "var(--card)",
            }}
          >
            {googleLoading ? (
              <Loader2 size={18} className="animate-spin" style={{ color: "var(--muted-foreground)" }} />
            ) : (
              <svg className="w-5 h-5" viewBox="0 0 24 24">
                <path d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92a5.06 5.06 0 01-2.2 3.32v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.1z" fill="#4285F4" />
                <path d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z" fill="#34A853" />
                <path d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.07H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.93l2.85-2.22.81-.62z" fill="#FBBC05" />
                <path d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.07l3.66 2.84c.87-2.6 3.3-4.53 6.16-4.53z" fill="#EA4335" />
              </svg>
            )}
            <span>{googleLoading ? "جاري تسجيل الدخول..." : "تسجيل الدخول بـ Google"}</span>
          </button>
          )}

          {/* Divider */}
          <div className="relative">
            <div
              className="absolute inset-0 flex items-center"
              aria-hidden="true"
            >
              <div className="w-full border-t" style={{ borderColor: "var(--border)" }} />
            </div>
            <div className="relative flex justify-center text-xs">
              <span
                className="px-3"
                style={{
                  background: "var(--background)",
                  color: "var(--muted-foreground)",
                }}
              >
                — أو —
              </span>
            </div>
          </div>

          {/* Email/password form */}
          <form onSubmit={handleEmail} className="space-y-4">
            <div className="space-y-1.5">
              <label
                htmlFor="email"
                className="block text-sm font-medium"
              >
                البريد الإلكتروني
              </label>
              <input
                id="email"
                type="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                className="w-full"
                placeholder="admin@mangaworld.app"
                required
                dir="ltr"
                disabled={loading || googleLoading}
              />
            </div>

            <div className="space-y-1.5">
              <label htmlFor="password" className="block text-sm font-medium">
                كلمة المرور
              </label>
              <div className="relative">
                <input
                  id="password"
                  type={showPw ? "text" : "password"}
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  className="w-full pe-10"
                  placeholder="••••••••"
                  required
                  dir="ltr"
                  disabled={loading || googleLoading}
                />
                <button
                  type="button"
                  onClick={() => setShowPw(!showPw)}
                  className="absolute end-3 top-1/2 -translate-y-1/2 p-0.5 rounded transition hover:bg-[var(--accent)]"
                  style={{ color: "var(--muted-foreground)" }}
                  aria-label={showPw ? "إخفاء كلمة المرور" : "إظهار كلمة المرور"}
                >
                  {showPw ? <EyeOff size={16} /> : <Eye size={16} />}
                </button>
              </div>
            </div>

            {error && (
              <div
                className="flex items-center gap-2.5 p-3 rounded-xl text-sm"
                style={{
                  background: "rgba(239,68,68,0.1)",
                  border: "1px solid rgba(239,68,68,0.2)",
                  color: "#ef4444",
                }}
              >
                <AlertCircle size={16} className="shrink-0" />
                <span>{error}</span>
              </div>
            )}
            {googleDetails && (
              <p className="text-[11px] font-mono break-all" dir="ltr" style={{ color: "var(--muted-foreground)" }}>
                {googleDetails}
              </p>
            )}

            <button
              type="submit"
              disabled={loading || googleLoading}
              className="w-full py-3 rounded-xl text-sm font-semibold transition hover:opacity-90 disabled:opacity-50 flex items-center justify-center gap-2"
              style={{
                background: "var(--primary)",
                color: "var(--primary-foreground)",
              }}
              aria-busy={loading}
            >
              {loading && <Loader2 size={16} className="animate-spin" />}
              {loading ? "جاري تسجيل الدخول..." : "تسجيل الدخول"}
            </button>
          </form>

          <p className="text-xs text-center" style={{ color: "var(--muted-foreground)" }}>
            استخدم حساب Google للدخول السريع أو بيانات Firebase Auth
          </p>
        </div>
      </div>
    </div>
  );
}
