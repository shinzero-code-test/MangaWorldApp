"use client";

import { useState, useEffect, useRef, useCallback } from "react";
import { useRouter } from "next/navigation";
import {
  Shield,
  ShieldCheck,
  Copy,
  Check,
  Loader2,
  AlertCircle,
  Smartphone,
} from "lucide-react";

type TwoFAState = "loading" | "setup" | "validate" | "done";

export default function TwoFAPage() {
  const [state, setState] = useState<TwoFAState>("loading");
  const [qrUrl, setQrUrl] = useState("");
  const [secret, setSecret] = useState("");
  const [otp, setOtp] = useState(["", "", "", "", "", ""]);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);
  const [copied, setCopied] = useState(false);
  const inputRefs = useRef<(HTMLInputElement | null)[]>([]);
  const redirectTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const router = useRouter();

  // Check 2FA status on mount
  useEffect(() => {
    fetch("/api/auth/2fa/status")
      .then((r) => {
        if (!r.ok) throw new Error("unauth");
        return r.json();
      })
      .then((data) => {
        if (data.enabled && data.verified) {
          router.replace("/dashboard");
        } else if (data.needsSetup) {
          // Need to set up 2FA — POST: setup generates/rotates state, so it must
          // never be a side-effecting GET (CSRF surface under SameSite=Lax).
          fetch("/api/auth/2fa/setup", { method: "POST" })
            .then(async (r) => {
              if (!r.ok) throw new Error("setup-failed");
              return r.json();
            })
            .then((setup) => {
              if (setup.alreadyEnabled) {
                setState("validate");
              } else {
                setQrUrl(setup.qrDataUrl);
                setSecret(setup.secret);
                setState("setup");
              }
            })
            .catch(() => {
              setError("تعذر تحضير المصادقة الثنائية. أعد المحاولة.");
              setState("validate");
            });
        } else if (data.needsValidation) {
          setState("validate");
        }
      })
      .catch(() => {
        router.replace("/login");
      });
    return () => {
      if (redirectTimer.current) clearTimeout(redirectTimer.current);
    };
  }, [router]);

  const otpValue = otp.join("");

  const handleOtpChange = useCallback(
    (index: number, value: string) => {
      if (!/^\d*$/.test(value)) return;
      const newOtp = [...otp];
      if (value.length > 1) {
        // Handle paste
        const digits = value.replace(/\D/g, "").slice(0, 6).split("");
        digits.forEach((d, i) => {
          if (i + index < 6) newOtp[i + index] = d;
        });
        setOtp(newOtp);
        const nextIdx = Math.min(index + digits.length, 5);
        inputRefs.current[nextIdx]?.focus();
      } else {
        newOtp[index] = value;
        setOtp(newOtp);
        if (value && index < 5) {
          inputRefs.current[index + 1]?.focus();
        }
      }
      setError("");
    },
    [otp]
  );

  const handleKeyDown = useCallback(
    (index: number, e: React.KeyboardEvent<HTMLInputElement>) => {
      if (e.key === "Backspace" && !otp[index] && index > 0) {
        inputRefs.current[index - 1]?.focus();
      }
    },
    [otp]
  );

  const handleCopySecret = async () => {
    try {
      await navigator.clipboard.writeText(secret);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      /* ignore */
    }
  };

  const handleVerify = async () => {
    if (otpValue.length !== 6) {
      setError("أدخل الرمز المكون من 6 أرقام");
      return;
    }
    setLoading(true);
    setError("");
    try {
      const endpoint =
        state === "setup"
          ? "/api/auth/2fa/verify"
          : "/api/auth/2fa/validate";
      const res = await fetch(endpoint, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ token: otpValue }),
      });
      const data = await res.json();
      if (!res.ok) {
        setError(data.error || "رمز التحقق غير صحيح");
        setOtp(["", "", "", "", "", ""]);
        inputRefs.current[0]?.focus();
        return;
      }
      setState("done");
      redirectTimer.current = setTimeout(() => {
        router.replace("/dashboard");
        router.refresh();
      }, 1200);
    } catch {
      setError("خطأ في الاتصال");
    } finally {
      setLoading(false);
    }
  };

  // Auto-submit when 6 digits entered
  useEffect(() => {
    if (otpValue.length === 6 && !loading) {
      handleVerify();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [otpValue]);

  // ─── Loading ───
  if (state === "loading") {
    return (
      <div
        className="min-h-screen flex items-center justify-center"
        style={{ background: "#0a0812" }}
      >
        <div className="flex flex-col items-center gap-3">
          <Loader2 size={28} className="animate-spin text-purple-400" />
          <p className="text-sm text-purple-300/60">جاري التحقق...</p>
        </div>
      </div>
    );
  }

  // ─── Success ───
  if (state === "done") {
    return (
      <div
        className="min-h-screen flex items-center justify-center"
        style={{ background: "#0a0812" }}
      >
        <div className="flex flex-col items-center gap-4 animate-in fade-in">
          <div
            className="w-16 h-16 rounded-2xl flex items-center justify-center"
            style={{
              background: "rgba(34,197,94,0.15)",
              border: "1px solid rgba(34,197,94,0.3)",
            }}
          >
            <ShieldCheck size={32} className="text-green-400" />
          </div>
          <p className="text-lg font-semibold text-white">تم التحقق بنجاح!</p>
          <p className="text-sm text-purple-300/60">
            جاري التحويل إلى لوحة التحكم...
          </p>
        </div>
      </div>
    );
  }

  return (
    <div className="min-h-screen flex" dir="rtl">
      {/* ── Decorative panel (desktop) ── */}
      <div
        className="hidden lg:flex lg:w-[55%] relative overflow-hidden flex-col items-center justify-center p-12"
        style={{ background: "#0a0812" }}
      >
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
        <div className="relative text-center z-10 space-y-6">
          <div
            className="w-20 h-20 rounded-3xl flex items-center justify-center mx-auto shadow-2xl"
            style={{
              background: "linear-gradient(135deg, #7c3aed, #4c1d95)",
              boxShadow: "0 20px 60px rgba(124,58,237,0.4)",
            }}
          >
            <Shield size={36} className="text-white" />
          </div>
          <div>
            <h1 className="text-3xl font-bold text-white tracking-tight">
              المصادقة الثنائية
            </h1>
            <p className="text-base mt-2" style={{ color: "rgba(167,139,250,0.9)" }}>
              حماية إضافية لحسابك الإداري
            </p>
          </div>
          <p
            className="text-sm max-w-sm mx-auto leading-relaxed"
            style={{ color: "rgba(167,139,250,0.5)" }}
          >
            تضيف المصادقة الثنائية طبقة أمان إضافية لحسابك من خلال طلب رمز تحقق
            من تطبيق المصادقة في كل مرة تسجل فيها الدخول.
          </p>

          {/* Feature cards */}
          <div className="flex gap-3 justify-center mt-6 flex-wrap">
            {[
              { icon: Shield, label: "حماية قوية" },
              { icon: Smartphone, label: "تطبيق المصادقة" },
              { icon: ShieldCheck, label: "تسجيل آمن" },
            ].map((f) => (
              <div
                key={f.label}
                className="flex items-center gap-2 px-4 py-2.5 rounded-xl"
                style={{
                  background: "rgba(139,92,246,0.12)",
                  border: "1px solid rgba(139,92,246,0.2)",
                }}
              >
                <f.icon size={16} className="text-purple-400" />
                <span className="text-xs text-purple-300">{f.label}</span>
              </div>
            ))}
          </div>
        </div>
      </div>

      {/* ── Main form panel ── */}
      <div
        className="flex-1 flex items-center justify-center p-6"
        style={{ background: "#0c0a14" }}
      >
        <div className="w-full max-w-md space-y-6">
          {/* Mobile header */}
          <div className="lg:hidden flex items-center gap-3 justify-center mb-4">
            <div
              className="w-10 h-10 rounded-xl flex items-center justify-center"
              style={{ background: "linear-gradient(135deg, #7c3aed, #4c1d95)" }}
            >
              <img src="/logo.png" alt="Logo" className="w-6 h-6 object-contain" />
            </div>
            <div>
              <p className="font-bold text-white">MangaWorld</p>
              <p className="text-xs text-purple-300/60">المصادقة الثنائية</p>
            </div>
          </div>

          {state === "setup" ? (
            /* ── SETUP FLOW ── */
            <div className="space-y-6">
              <div className="text-center">
                <div
                  className="w-14 h-14 rounded-2xl flex items-center justify-center mx-auto mb-4"
                  style={{
                    background: "rgba(139,92,246,0.15)",
                    border: "1px solid rgba(139,92,246,0.25)",
                  }}
                >
                  <Shield size={24} className="text-purple-400" />
                </div>
                <h2 className="text-xl font-bold text-white">
                  إعداد المصادقة الثنائية
                </h2>
                <p className="text-sm mt-1.5 text-purple-300/60">
                  امسح رمز QR بتطبيق المصادقة (Google Authenticator أو Authy)
                </p>
              </div>

              {/* Steps */}
              <div className="space-y-4">
                {/* Step 1: QR */}
                <div
                  className="rounded-2xl p-5"
                  style={{
                    background: "rgba(139,92,246,0.06)",
                    border: "1px solid rgba(139,92,246,0.12)",
                  }}
                >
                  <div className="flex items-center gap-2 mb-3">
                    <span
                      className="w-6 h-6 rounded-full flex items-center justify-center text-xs font-bold"
                      style={{
                        background: "rgba(139,92,246,0.2)",
                        color: "#a78bfa",
                      }}
                    >
                      ١
                    </span>
                    <span className="text-sm font-medium text-purple-200">
                      امسح رمز QR
                    </span>
                  </div>
                  <div className="flex justify-center">
                    {qrUrl ? (
                      <div
                        className="rounded-xl p-3"
                        style={{
                          background: "rgba(0,0,0,0.3)",
                          border: "1px solid rgba(139,92,246,0.15)",
                        }}
                      >
                        <img
                          src={qrUrl}
                          alt="QR Code"
                          className="w-56 h-56"
                          style={{ imageRendering: "pixelated" }}
                        />
                      </div>
                    ) : (
                      <Loader2
                        size={24}
                        className="animate-spin text-purple-400"
                      />
                    )}
                  </div>
                </div>

                {/* Step 2: Manual key */}
                <div
                  className="rounded-2xl p-5"
                  style={{
                    background: "rgba(139,92,246,0.06)",
                    border: "1px solid rgba(139,92,246,0.12)",
                  }}
                >
                  <div className="flex items-center gap-2 mb-3">
                    <span
                      className="w-6 h-6 rounded-full flex items-center justify-center text-xs font-bold"
                      style={{
                        background: "rgba(139,92,246,0.2)",
                        color: "#a78bfa",
                      }}
                    >
                      ٢
                    </span>
                    <span className="text-sm font-medium text-purple-200">
                      أو أدخل المفتاح يدوياً
                    </span>
                  </div>
                  <div
                    className="flex items-center gap-2 p-3 rounded-xl"
                    style={{
                      background: "rgba(0,0,0,0.3)",
                      border: "1px solid rgba(139,92,246,0.15)",
                    }}
                  >
                    <code
                      className="flex-1 text-sm font-mono tracking-widest text-center"
                      style={{ color: "#a78bfa" }}
                      dir="ltr"
                    >
                      {secret
                        ? secret.match(/.{1,4}/g)?.join(" ")
                        : "..."}
                    </code>
                    <button
                      onClick={handleCopySecret}
                      className="p-1.5 rounded-lg transition hover:bg-white/10"
                      aria-label="نسخ المفتاح"
                    >
                      {copied ? (
                        <Check size={14} className="text-green-400" />
                      ) : (
                        <Copy size={14} className="text-purple-400" />
                      )}
                    </button>
                  </div>
                </div>

                {/* Step 3: Enter OTP */}
                <div
                  className="rounded-2xl p-5"
                  style={{
                    background: "rgba(139,92,246,0.06)",
                    border: "1px solid rgba(139,92,246,0.12)",
                  }}
                >
                  <div className="flex items-center gap-2 mb-4">
                    <span
                      className="w-6 h-6 rounded-full flex items-center justify-center text-xs font-bold"
                      style={{
                        background: "rgba(139,92,246,0.2)",
                        color: "#a78bfa",
                      }}
                    >
                      ٣
                    </span>
                    <span className="text-sm font-medium text-purple-200">
                      أدخل رمز التحقق
                    </span>
                  </div>

                  {/* OTP inputs */}
                  <div className="flex gap-2 justify-center" dir="ltr">
                    {otp.map((digit, i) => (
                      <input
                        key={i}
                        ref={(el) => { inputRefs.current[i] = el; }}
                        type="text"
                        inputMode="numeric"
                        maxLength={6}
                        value={digit}
                        onChange={(e) => handleOtpChange(i, e.target.value)}
                        onKeyDown={(e) => handleKeyDown(i, e)}
                        onFocus={(e) => e.target.select()}
                        className="w-11 h-13 text-center text-lg font-mono font-bold rounded-xl border-2 outline-none transition-all duration-200 focus:scale-105"
                        style={{
                          background: digit ? "rgba(139,92,246,0.15)" : "rgba(0,0,0,0.3)",
                          borderColor: digit
                            ? "rgba(139,92,246,0.5)"
                            : "rgba(139,92,246,0.15)",
                          color: "#e2d6ff",
                          caretColor: "#a78bfa",
                        }}
                      />
                    ))}
                  </div>
                </div>
              </div>

              {/* Error */}
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

              {/* Submit button */}
              <button
                onClick={handleVerify}
                disabled={loading || otpValue.length !== 6}
                className="w-full py-3.5 rounded-xl text-sm font-semibold transition-all duration-200 hover:opacity-90 disabled:opacity-40 disabled:cursor-not-allowed flex items-center justify-center gap-2"
                style={{
                  background: "linear-gradient(135deg, #7c3aed, #6d28d9)",
                  color: "white",
                  boxShadow:
                    otpValue.length === 6
                      ? "0 8px 32px rgba(124,58,237,0.4)"
                      : "none",
                }}
              >
                {loading && <Loader2 size={16} className="animate-spin" />}
                {loading
                  ? "جاري التحقق..."
                  : "تفعيل المصادقة الثنائية"}
              </button>
            </div>
          ) : (
            /* ── VALIDATE FLOW (login with existing 2FA) ── */
            <div className="space-y-6">
              <div className="text-center">
                <div
                  className="w-14 h-14 rounded-2xl flex items-center justify-center mx-auto mb-4"
                  style={{
                    background: "rgba(139,92,246,0.15)",
                    border: "1px solid rgba(139,92,246,0.25)",
                  }}
                >
                  <ShieldCheck size={24} className="text-purple-400" />
                </div>
                <h2 className="text-xl font-bold text-white">
                  رمز التحقق
                </h2>
                <p className="text-sm mt-1.5 text-purple-300/60">
                  أدخل الرمز من تطبيق المصادقة للمتابعة
                </p>
              </div>

              {/* OTP inputs */}
              <div
                className="rounded-2xl p-6"
                style={{
                  background: "rgba(139,92,246,0.06)",
                  border: "1px solid rgba(139,92,246,0.12)",
                }}
              >
                <div className="flex gap-2.5 justify-center" dir="ltr">
                  {otp.map((digit, i) => (
                    <input
                      key={i}
                      ref={(el) => { inputRefs.current[i] = el; }}
                      type="text"
                      inputMode="numeric"
                      maxLength={6}
                      value={digit}
                      onChange={(e) => handleOtpChange(i, e.target.value)}
                      onKeyDown={(e) => handleKeyDown(i, e)}
                      onFocus={(e) => e.target.select()}
                      autoFocus={i === 0}
                      className="w-12 h-14 text-center text-xl font-mono font-bold rounded-xl border-2 outline-none transition-all duration-200 focus:scale-105"
                      style={{
                        background: digit ? "rgba(139,92,246,0.15)" : "rgba(0,0,0,0.3)",
                        borderColor: digit
                          ? "rgba(139,92,246,0.5)"
                          : "rgba(139,92,246,0.15)",
                        color: "#e2d6ff",
                        caretColor: "#a78bfa",
                      }}
                    />
                  ))}
                </div>
              </div>

              {/* Error */}
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

              {/* Submit */}
              <button
                onClick={handleVerify}
                disabled={loading || otpValue.length !== 6}
                className="w-full py-3.5 rounded-xl text-sm font-semibold transition-all duration-200 hover:opacity-90 disabled:opacity-40 disabled:cursor-not-allowed flex items-center justify-center gap-2"
                style={{
                  background: "linear-gradient(135deg, #7c3aed, #6d28d9)",
                  color: "white",
                  boxShadow:
                    otpValue.length === 6
                      ? "0 8px 32px rgba(124,58,237,0.4)"
                      : "none",
                }}
              >
                {loading && <Loader2 size={16} className="animate-spin" />}
                {loading ? "جاري التحقق..." : "تأكيد والمتابعة"}
              </button>

              <p className="text-xs text-center text-purple-300/40">
                افتح تطبيق Google Authenticator أو Authy واحصل على الرمز
              </p>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
