import type { Metadata, Viewport } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "MangaWorld Admin | لوحة تحكم مانجا وورلد",
  description: "لوحة إدارة تطبيق مانجا وورلد",
  manifest: "/manifest.json",
  appleWebApp: {
    capable: true,
    statusBarStyle: "default",
    title: "MangaWorld Admin",
  },
};

export const viewport: Viewport = {
  themeColor: "#7c3aed",
};

// Applies the saved theme before hydration so light-mode users never see a dark flash.
const themeInitScript = `
try {
  var t = localStorage.getItem("mw-theme");
  if (t === "light") document.documentElement.classList.remove("dark");
} catch (e) {}
`;

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="ar" dir="rtl" className="dark" suppressHydrationWarning>
      <head>
        <script dangerouslySetInnerHTML={{ __html: themeInitScript }} />
      </head>
      <body className="antialiased min-h-screen">{children}</body>
    </html>
  );
}
