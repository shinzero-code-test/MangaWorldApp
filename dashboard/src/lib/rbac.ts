export const ROLES = {
  VIEWER: "viewer",
  MODERATOR: "moderator",
  SUPER_ADMIN: "super-admin",
} as const;

export type UserRole = (typeof ROLES)[keyof typeof ROLES];

export const ROLE_HIERARCHY: Record<UserRole, number> = {
  viewer: 0,
  moderator: 1,
  "super-admin": 2,
};

export const ROLE_LABELS: Record<UserRole, string> = {
  viewer: "مشاهد",
  moderator: "مشرف",
  "super-admin": "مدير عام",
};

export const ROLE_COLORS: Record<UserRole, string> = {
  viewer: "bg-gray-100 text-gray-800",
  moderator: "bg-blue-100 text-blue-800",
  "super-admin": "bg-red-100 text-red-800",
};

export function hasPermission(
  userRole: UserRole,
  requiredRole: UserRole
): boolean {
  return ROLE_HIERARCHY[userRole] >= ROLE_HIERARCHY[requiredRole];
}
