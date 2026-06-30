export type Role = "viewer" | "moderator" | "super-admin";

const RANK: Record<Role, number> = {
  viewer:        0,
  moderator:     1,
  "super-admin": 2,
};

export function hasRole(userRole: string, requiredRole: Role): boolean {
  return (RANK[userRole as Role] ?? 0) >= RANK[requiredRole];
}

export function getRoleLabel(role: string): string {
  const labels: Record<string, string> = {
    "super-admin": "مدير عام",
    moderator:     "مشرف",
    viewer:        "مشاهد",
  };
  return labels[role] ?? role;
}
