// Real lint: FlatCompat bridge to next/core-web-vitals (documented Next 15
// flat-config usage). `npm run lint` enforces it; Vercel builds keep
// `ignoreDuringBuilds` until the remaining `any`-debt is typed (F6).
import { dirname } from "path";
import { fileURLToPath } from "url";
import { FlatCompat } from "@eslint/eslintrc";

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);

const compat = new FlatCompat({ baseDirectory: __dirname });

export default [
  { ignores: [".next/**", "out/**", "build/**", "next-env.d.ts"] },
  ...compat.extends("next/core-web-vitals"),
];
