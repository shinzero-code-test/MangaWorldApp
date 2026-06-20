"use client";

import { useState, useEffect } from "react";
import { useRouter } from "next/navigation";

export default function LoginPage() {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);
  const [googleLoading, setGoogleLoading] = useState(false);
  const router = useRouter();

  // Load Firebase client SDK
  useEffect(() => {
    const script = document.createElement("script");
    script.src = "https://www.gstatic.com/firebasejs/11.0.0/firebase-app-compat.js";
    script.onload = () => {
      const script2 = document.createElement("script");
      script2.src = "https://www.gstatic.com/firebasejs/11.0.0/firebase-auth-compat.js";
      document.head.appendChild(script2);
    };
    document.head.appendChild(script);
  }, []);

  const getFirebaseAuth = () => {
    const firebase = (window as any).firebase;
    if (!firebase) return null;
    if (!firebase.apps.length) {
      firebase.initializeApp({
        apiKey: process.env.NEXT_PUBLIC_FIREBASE_CLIENT_API_KEY,
        authDomain: process.env.NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN,
        projectId: process.env.NEXT_PUBLIC_FIREBASE_PROJECT_ID,
      });
    }
    return firebase.auth();
  };

  const handleGoogleLogin = async () => {
    setGoogleLoading(true);
    setError("");
    try {
      const auth = getFirebaseAuth();
      if (!auth) {
        setError("جاري تحميل Firebase...");
        setGoogleLoading(false);
        return;
      }

      const provider = new (window as any).firebase.auth.GoogleAuthProvider();
      const result = await auth.signInWithPopup(provider);
      const idToken = await result.user.getIdToken();

      const res = await fetch("/api/auth/google", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ idToken }),
      });

      const data = await res.json();
      if (!res.ok) {
        setError(data.error || "خطأ في تسجيل الدخول بـ Google");
        return;
      }

      router.push("/dashboard");
      router.refresh();
    } catch (err: any) {
      if (err.code === "auth/popup-closed-by-user") {
        setError("تم إغلاق نافذة تسجيل الدخول");
      } else {
        setError(err.message || "خطأ في تسجيل الدخول بـ Google");
      }
    } finally {
      setGoogleLoading(false);
    }
  };

  const handleEmailLogin = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true);
    setError("");

    try {
      const auth = getFirebaseAuth();
      if (!auth) {
        setError("جاري تحميل Firebase...");
        setLoading(false);
        return;
      }

      // Sign in with Firebase client SDK
      const result = await auth.signInWithEmailAndPassword(email, password);
      const idToken = await result.user.getIdToken();

      // Send ID token to create session cookie
      const res = await fetch("/api/auth/google", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ idToken }),
      });

      const data = await res.json();
      if (!res.ok) {
        setError(data.error || "خطأ في تسجيل الدخول");
        return;
      }

      router.push("/dashboard");
      router.refresh();
    } catch (err: any) {
      const messages: Record<string, string> = {
        "auth/user-not-found": "البريد الإلكتروني غير مسجل",
        "auth/wrong-password": "كلمة المرور غير صحيحة",
        "auth/invalid-credential": "بيانات تسجيل الدخول غير صحيحة",
        "auth/too-many-requests": "تم تقييد الحساب مؤقتاً. حاول مرة أخرى لاحقاً",
        "auth/invalid-email": "البريد الإلكتروني غير صالح",
        "auth/popup-closed-by-user": "تم إغلاق نافذة تسجيل الدخول",
      };
      setError(messages[err.code] || err.message || "خطأ في تسجيل الدخول");
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="min-h-screen flex items-center justify-center bg-[var(--background)]" dir="rtl">
      <div className="w-full max-w-md p-8 space-y-6 bg-[var(--card)] rounded-2xl shadow-xl border border-[var(--border)]">
        <div className="text-center">
          <h1 className="text-3xl font-bold text-[var(--foreground)]">MangaWorld</h1>
          <p className="text-[var(--muted-foreground)] mt-2">لوحة التحكم</p>
        </div>

        {/* Google Sign-In Button */}
        <button
          onClick={handleGoogleLogin}
          disabled={googleLoading || loading}
          className="w-full flex items-center justify-center gap-3 py-3 rounded-lg border border-[var(--border)] bg-white text-gray-700 font-medium hover:bg-gray-50 transition disabled:opacity-50"
        >
          {googleLoading ? (
            <span className="w-5 h-5 border-2 border-gray-400 border-t-transparent rounded-full animate-spin" />
          ) : (
            <svg className="w-5 h-5" viewBox="0 0 24 24">
              <path d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92a5.06 5.06 0 01-2.2 3.32v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.1z" fill="#4285F4"/>
              <path d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z" fill="#34A853"/>
              <path d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.07H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.93l2.85-2.22.81-.62z" fill="#FBBC05"/>
              <path d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.07l3.66 2.84c.87-2.6 3.3-4.53 6.16-4.53z" fill="#EA4335"/>
            </svg>
          )}
          <span>{googleLoading ? "جاري تسجيل الدخول..." : "تسجيل الدخول بـ Google"}</span>
        </button>

        {/* Divider */}
        <div className="relative">
          <div className="absolute inset-0 flex items-center">
            <div className="w-full border-t border-[var(--border)]" />
          </div>
          <div className="relative flex justify-center text-sm">
            <span className="px-2 bg-[var(--card)] text-[var(--muted-foreground)]">أو</span>
          </div>
        </div>

        {/* Email/Password Form */}
        <form onSubmit={handleEmailLogin} className="space-y-4">
          <div>
            <label className="block text-sm font-medium text-[var(--foreground)] mb-1">
              البريد الإلكتروني
            </label>
            <input
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              className="w-full px-4 py-3 rounded-lg border border-[var(--border)] bg-[var(--background)] text-[var(--foreground)] focus:ring-2 focus:ring-[var(--ring)] focus:border-transparent outline-none transition"
              placeholder="admin@mangaworld.com"
              required
              dir="ltr"
            />
          </div>

          <div>
            <label className="block text-sm font-medium text-[var(--foreground)] mb-1">
              كلمة المرور
            </label>
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              className="w-full px-4 py-3 rounded-lg border border-[var(--border)] bg-[var(--background)] text-[var(--foreground)] focus:ring-2 focus:ring-[var(--ring)] focus:border-transparent outline-none transition"
              placeholder="••••••••"
              required
              dir="ltr"
            />
          </div>

          {error && (
            <div className="p-3 rounded-lg bg-red-500/10 border border-red-500/20 text-red-400 text-sm text-center">
              {error}
            </div>
          )}

          <button
            type="submit"
            disabled={loading || googleLoading}
            className="w-full py-3 rounded-lg bg-[var(--primary)] text-[var(--primary-foreground)] font-medium hover:opacity-90 transition disabled:opacity-50"
          >
            {loading ? "جاري تسجيل الدخول..." : "تسجيل الدخول"}
          </button>
        </form>

        <p className="text-xs text-center text-[var(--muted-foreground)]">
          استخدم حساب Google للدخول السريع، أو أدخل بيانات حساب Firebase Auth
        </p>
      </div>
    </div>
  );
}
