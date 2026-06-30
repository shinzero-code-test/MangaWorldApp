# MangaWorld Admin Dashboard — لوحة تحكم مانجا وورلد

لوحة إدارة شاملة لتطبيق MangaWorld، مبنية بـ Next.js 15 و Tailwind CSS v4.

---

## ✨ المميزات

- **تصميم داكن فاخر** — نظام ألوان Violet/Purple مع دعم Light/Dark mode
- **عربي أولاً** — RTL كامل، خط IBM Plex Sans Arabic، أرقام عربية
- **بدون Emoji** — جميع الأيقونات من Lucide React
- **Recharts** — كل المخططات بـ recharts (لا CSS هاكات)
- **Firebase Admin** — Auth، Firestore، FCM، Crashlytics، Storage
- **Role-based access** — super-admin | moderator | viewer

---

## 🛠️ التقنيات

| التقنية | الإصدار |
|---------|---------|
| Next.js | 15 (App Router) |
| Tailwind CSS | v4 (CSS-first) |
| TypeScript | strict mode |
| lucide-react | ^0.400.0 |
| recharts | ^2.12.7 |
| Firebase Admin | ^12 |

---

## 🚀 البداية السريعة

### 1. المتطلبات
- Node.js 20+
- حساب Firebase مع Firestore + Auth + FCM

### 2. التثبيت

```bash
npm install
```

### 3. متغيرات البيئة

```bash
cp .env.local.example .env.local
```

عدّل `.env.local` بإضافة بيانات Firebase:

```env
FIREBASE_PROJECT_ID=your-project-id
FIREBASE_CLIENT_EMAIL=firebase-adminsdk@your-project-id.iam.gserviceaccount.com
FIREBASE_PRIVATE_KEY="-----BEGIN PRIVATE KEY-----\n...\n-----END PRIVATE KEY-----\n"
FIREBASE_STORAGE_BUCKET=your-project-id.appspot.com

NEXT_PUBLIC_FIREBASE_API_KEY=AIza...
NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN=your-project-id.firebaseapp.com
NEXT_PUBLIC_FIREBASE_PROJECT_ID=your-project-id
```

### 4. التشغيل المحلي

```bash
npm run dev
```

افتح [http://localhost:3000](http://localhost:3000)

---

## 📁 هيكل المشروع

```
src/
├── app/
│   ├── globals.css              # Design tokens + fonts
│   ├── layout.tsx               # Root layout (Arabic, RTL, dark)
│   ├── login/page.tsx           # Split login page
│   ├── dashboard/
│   │   ├── layout.tsx           # Auth guard + sidebar + header
│   │   ├── page.tsx             # Overview with KPIs + sparklines
│   │   ├── users/               # Users table + detail
│   │   ├── moderation/          # Reports + banned keywords
│   │   ├── community/           # Comments + reviews
│   │   ├── analytics/           # Recharts bar/pie charts
│   │   ├── crashlytics/         # Crash gauge + issue cards
│   │   ├── remote-config/       # Accordion parameter groups
│   │   ├── notifications/       # Send FCM + history
│   │   ├── achievements/        # Achievement grid + goals
│   │   ├── performance/         # Trace table + screen charts
│   │   ├── storage/             # Storage donut chart
│   │   ├── data/                # Firestore browser
│   │   ├── settings/            # App settings with toggles
│   │   └── releases/            # App releases
│   └── api/                     # All API routes (unchanged)
├── components/
│   ├── layout/
│   │   ├── sidebar.tsx          # Dark sidebar, grouped nav
│   │   └── header.tsx           # Clock + breadcrumb + user menu
│   ├── ui/index.tsx             # StatusBadge, Toggle, ConfirmDialog...
│   ├── providers/theme-provider.tsx
│   └── shared/error-boundary.tsx
└── lib/
    ├── firebase-admin.ts        # Admin SDK singleton
    ├── auth.ts                  # Session cookie auth
    ├── rbac.ts                  # Role rank checks
    ├── constants.ts             # App constants
    └── utils.ts                 # Formatters + helpers
```

---

## 🎨 نظام الألوان

| المتغير | داكن | فاتح |
|---------|------|------|
| `--background` | `#0d0b14` | `#f8f7ff` |
| `--primary` | `#8b5cf6` | `#7c3aed` |
| `--card` | `#16131f` | `#ffffff` |
| `--border` | `#2d2540` | `#e5e3f0` |
| `--sidebar-bg` | `#0a0812` | `#1a1625` |

---

## 🔐 الأدوار والصلاحيات

| الدور | الصلاحيات |
|-------|----------|
| `super-admin` | كل الصفحات |
| `moderator` | المستخدمون، الإشراف، المجتمع، التحليلات |
| `viewer` | نظرة عامة، التحليلات، الإنجازات |

---

## 📦 النشر على Vercel

```bash
npm install -g vercel
vercel --prod
```

أضف متغيرات البيئة في لوحة Vercel أو عبر CLI:

```bash
vercel env add FIREBASE_PRIVATE_KEY
```
