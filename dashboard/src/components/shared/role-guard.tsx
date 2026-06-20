"use client";

import { ReactNode } from "react";

interface RoleGuardProps {
  role: "super-admin" | "moderator" | "viewer";
  userRole: string;
  children: ReactNode;
  fallback?: ReactNode;
}

const ROLE_HIERARCHY: Record<string, number> = {
  viewer: 0,
  moderator: 1,
  "super-admin": 2,
};

export function RoleGuard({ role, userRole, children, fallback }: RoleGuardProps) {
  const hasAccess = (ROLE_HIERARCHY[userRole] || 0) >= (ROLE_HIERARCHY[role] || 0);

  if (!hasAccess) {
    return fallback ? <>{fallback}</> : null;
  }

  return <>{children}</>;
}
