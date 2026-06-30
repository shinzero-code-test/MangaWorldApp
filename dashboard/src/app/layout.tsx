import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "MangaWorld Admin | لوحة تحكم مانجا وورلد",
  description: "لوحة إدارة تطبيق مانجا وورلد",
  manifest: "/manifest.json",
  themeColor: "#7c3aed",
  appleWebApp: {
    capable: true,
    statusBarStyle: "default",
    title: "MangaWorld Admin",
  },
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="ar" dir="rtl" className="dark">
      <body className="antialiased min-h-screen">{children}</body>
    </html>
  );
}
