import type { Metadata, Viewport } from "next";
import { headers } from "next/headers";
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

// Registers the installability-only service worker (no caching — see public/sw.js).
const swRegisterScript = `
if ("serviceWorker" in navigator) {
  window.addEventListener("load", function () {
    navigator.serviceWorker.register("/sw.js").catch(function () {});
  });
}
`;

export default async function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  // U-31: nonce minted by middleware.ts (x-nonce). Next.js framework scripts
  // pick the same value up automatically; the two inline boot scripts below
  // must carry it explicitly or the nonced script-src blocks them.
  const nonce = (await headers()).get("x-nonce") ?? "";
  return (
    <html lang="ar" dir="rtl" className="dark" suppressHydrationWarning>
      <head>
        <script nonce={nonce} dangerouslySetInnerHTML={{ __html: themeInitScript }} />
        <script nonce={nonce} dangerouslySetInnerHTML={{ __html: swRegisterScript }} />
      </head>
      <body className="antialiased min-h-screen">{children}</body>
    </html>
  );
}
