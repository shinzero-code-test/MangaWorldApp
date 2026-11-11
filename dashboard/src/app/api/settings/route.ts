import { NextRequest, NextResponse } from "next/server";
import { getAdminRemoteConfig } from "@/lib/firebase-admin";
import { requireRole } from "@/lib/auth";
import { validateRemoteConfigParams, validateSourceDomainParams } from "@/lib/validate";
import { genericErrorResponse, logSecurityEvent } from "@/lib/security";

export const dynamic = 'force-dynamic';

export async function GET() {
  try {
    await requireRole("super-admin");
    const template = await getAdminRemoteConfig().getTemplate();
    const settings: Record<string, any> = {};
    for (const [key, param] of Object.entries(template.parameters)) {
      if (param.defaultValue && 'value' in param.defaultValue) {
        let val: any = param.defaultValue.value;
        if (val === "true" || val === "false") val = val === "true";
        else if (!isNaN(Number(val)) && val.trim() !== "") val = Number(val);
        settings[key] = val;
      }
    }
    return NextResponse.json({ settings });
  } catch (error: unknown) {
    const msg = error instanceof Error ? error.message : String(error);
    // Remote Config etag/version conflicts: concurrent edit lost, retry on fresh read.
    if (/etag|version.*match|already exists|out of date|conflict/i.test(msg)) {
      return NextResponse.json({ error: "تعارض مع تعديل آخر. أعد التحميل وحاول مجدداً." }, { status: 409 });
    }
    const { body, status } = genericErrorResponse(error);
    return NextResponse.json(body, { status });
  }
}

export async function PUT(request: NextRequest) {
  try {
    const admin = await requireRole("super-admin");
    const { settings } = await request.json();
    // Config-injection guard: known key charset, primitive values, bounded count/size.
    const validation = validateRemoteConfigParams(settings);
    if (!validation.ok) {
      return NextResponse.json({ error: validation.error }, { status: 400 });
    }
    // U-30: second PUT path for source base URLs — same https +
    // origin-only + allow-list gate as remote-config (both PUTs), plus the
    // API-host overrides (`source_<id>_api_host`).
    const urlValidation = validateSourceDomainParams(settings);
    if (!urlValidation.ok) {
      return NextResponse.json({ error: urlValidation.error }, { status: 400 });
    }
    const template = await getAdminRemoteConfig().getTemplate();

    for (const [key, value] of Object.entries(settings)) {
      if (!template.parameters) template.parameters = {};
      template.parameters[key] = {
        defaultValue: { value: String(value) },
        valueType: typeof value === "boolean" ? "BOOLEAN" : typeof value === "number" ? "NUMBER" : "STRING"
      };
    }

    await getAdminRemoteConfig().publishTemplate(template);
    // Note: the fetched template carries its etag, so a concurrent publish
    // fails here instead of silently winning (last-write-wins avoided).
    // U-30: audit every publish (same logger users/route.ts uses).
    await logSecurityEvent("remote_config_publish", {
      by: admin.uid,
      keys: Object.keys(settings as Record<string, unknown>).sort(),
    });
    return NextResponse.json({ success: true });
  } catch (error: unknown) {
    const { body, status } = genericErrorResponse(error, "فشل حفظ الإعدادات");
    return NextResponse.json(body, { status });
  }
}
