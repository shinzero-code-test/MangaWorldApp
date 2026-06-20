"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";

interface UserInfo {
  uid: string;
  email: string;
  role: string;
}

export function useAuth() {
  const [user, setUser] = useState<UserInfo | null>(null);
  const [loading, setLoading] = useState(true);
  const router = useRouter();

  useEffect(() => {
    fetch("/api/auth/me")
      .then((res) => {
        if (!res.ok) throw new Error("Unauthorized");
        return res.json();
      })
      .then((data) => { setUser(data); setLoading(false); })
      .catch(() => { router.push("/login"); setLoading(false); });
  }, [router]);

  const hasPermission = (minRole: "viewer" | "moderator" | "super-admin") => {
    if (!user) return false;
    const hierarchy: Record<string, number> = { viewer: 0, moderator: 1, "super-admin": 2 };
    return (hierarchy[user.role] ?? 0) >= (hierarchy[minRole] ?? 0);
  };

  return { user, loading, hasPermission };
}
