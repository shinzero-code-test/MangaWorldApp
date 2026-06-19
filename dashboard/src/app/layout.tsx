import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "MangaWorld Admin",
  description: "Admin dashboard for MangaWorld",
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="ar" dir="rtl" className="dark">
      <body className="antialiased">{children}</body>
    </html>
  );
}
