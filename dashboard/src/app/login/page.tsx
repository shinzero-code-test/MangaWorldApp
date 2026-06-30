"use client";

import { useState, useEffect } from "react";
import { useRouter } from "next/navigation";
import { AlertCircle, Eye, EyeOff, BookOpen, Loader2 } from "lucide-react";

export default function LoginPage() {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [showPw, setShowPw] = useState(false);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);
  const [googleLoading, setGoogleLoading] = useState(false);
  const router = useRouter();

  useEffect(() => {
    const s1 = document.createElement("script");
    s1.src = "https://www.gstatic.com/firebasejs/11.0.0/firebase-app-compat.js";
    s1.onload = () => {
      const s2 = document.createElement("script");
      s2.src = "https://www.gstatic.com/firebasejs/11.0.0/firebase-auth-compat.js";
      document.head.appendChild(s2);
    };
    document.head.appendChild(s1);
  }, []);

  const getAuth = () => {
    const fb = (window as any).firebase;
    if (!fb) return null;
    if (!fb.apps.length) {
      fb.initializeApp({
        apiKey: process.env.NEXT_PUBLIC_FIREBASE_CLIENT_API_KEY,
        authDomain: process.env.NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN,
        projectId: process.env.NEXT_PUBLIC_FIREBASE_PROJECT_ID,
      });
    }
    return fb.auth();
  };

  const handleSession = async (idToken: string) => {
    const res = await fetch("/api/auth/google", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ idToken }),
    });
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || "خطأ في تسجيل الدخول");
    router.push("/2fa");
    router.refresh();
  };

  const handleGoogle = async () => {
    setGoogleLoading(true);
    setError("");
    try {
      const auth = getAuth();
      if (!auth) { setError("جاري تحميل Firebase..."); return; }
      const provider = new (window as any).firebase.auth.GoogleAuthProvider();
      const result = await auth.signInWithPopup(provider);
      await handleSession(await result.user.getIdToken());
    } catch (e: any) {
      if (e.code === "auth/popup-closed-by-user") {
        setError("تم إغلاق نافذة تسجيل الدخول");
      } else {
        setError(e.message || "خطأ في تسجيل الدخول بـ Google");
      }
    } finally {
      setGoogleLoading(false);
    }
  };

  const handleEmail = async (ev: React.FormEvent) => {
    ev.preventDefault();
    setLoading(true);
    setError("");
    try {
      const auth = getAuth();
      if (!auth) { setError("جاري تحميل Firebase..."); return; }
      const result = await auth.signInWithEmailAndPassword(email, password);
      await handleSession(await result.user.getIdToken());
    } catch (e: any) {
      const msgs: Record<string, string> = {
        "auth/user-not-found": "البريد الإلكتروني غير مسجل",
        "auth/wrong-password": "كلمة المرور غير صحيحة",
        "auth/invalid-credential": "بيانات تسجيل الدخول غير صحيحة",
        "auth/too-many-requests": "تم تقييد الحساب مؤقتاً. حاول لاحقاً",
        "auth/invalid-email": "البريد الإلكتروني غير صالح",
      };
      setError(msgs[e.code] || e.message || "خطأ في تسجيل الدخول");
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

          {/* Floating stat cards */}
          <div className="flex gap-4 justify-center mt-8 flex-wrap">
            {[
              { label: "مستخدم", value: "٢٤ألف" },
              { label: "فصل", value: "١٢ألف" },
              { label: "تقييم", value: "٩٨%" },
            ].map((s) => (
              <div
                key={s.label}
                className="px-4 py-3 rounded-xl text-center"
                style={{
                  background: "rgba(139,92,246,0.12)",
                  border: "1px solid rgba(139,92,246,0.2)",
                }}
              >
                <p className="text-xl font-bold text-white">{s.value}</p>
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

          {/* Google button */}
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
