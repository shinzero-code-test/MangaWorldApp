# ── Jsoup ──────────────────────────────────────────────────────────────────────
-keep public class org.jsoup.** { *; }

# ── OkHttp ─────────────────────────────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
-keep class okhttp3.** { *; }

# ── Rhino (Phase 3 script sandbox) ─────────────────────────────────────────────
# java.beans is referenced by JavaToJSONConverters but never reached: no Java
# object crosses the bridge (results are validated primitives; a leaked host
# object is a hard failure). Likewise the tools/debugger GUI (java.awt) is
# unreachable from interpreter-only operation — R8 strips it; these dontwarns
# cover the analysis order, not kept code. Deliberately NO broad keep here: one
# kept the whole runtime (including the Swing debugger) and failed the release
# build on java.awt. Reachable interpreter code is kept via our direct
# references (Context/ContextFactory/Scriptable*/BaseFunction/...).
-dontwarn java.beans.**
-dontwarn java.awt.**
# Reflective names (v9.1.6): Rhino locates these by exact class-name STRING via
# Kit.classOrNull, so R8 must neither rename NOR strip them — our direct
# references don't cover them. Verified against rhino-1.7.15 sources:
# - VMBridge.makeInstance probes VMBridge_custom, then jdk18.VMBridge_jdk18.
#   When both miss, <clinit> throws IllegalStateException, and EVERY
#   Context.enter dies with ExceptionInInitializerError — the exact 9.1.5
#   fleet failure (manonga canary: `sync manonga failed:
#   java.lang.ExceptionInInitializerError: null`). Full -keep (not just
#   -keepnames): newInstanceOrNull needs the no-arg constructor to survive too.
# - Context.createCompiler/createInterpreter probe optimizer.Codegen (skipped
#   at optimizationLevel=-1, stays stripped) and Interpreter (used: keep).
# - ScriptRuntime lazily loads RegExp/Continuation/typed-arrays by literal;
#   XML* only registers when FEATURE_E4X is on (ours is off — not kept).
# - Bridge functions extend BaseFunction directly (no InterfaceAdapter, so no
#   JavaAdapter bytecode path). tools/debugger + tools/shell + optimizer +
#   xml + commonjs stay stripped.
-keep class org.mozilla.javascript.VMBridge { *; }
-keep class org.mozilla.javascript.jdk18.VMBridge_jdk18 { *; }
-keep class org.mozilla.javascript.Interpreter { *; }
-keep class org.mozilla.javascript.regexp.NativeRegExp { *; }
-keep class org.mozilla.javascript.NativeContinuation { *; }
-keep class org.mozilla.javascript.typedarrays.** { *; }

# ── Kotlin coroutines ──────────────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# ── Room ───────────────────────────────────────────────────────────────────────
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keepclassmembers class * extends androidx.room.RoomDatabase {
    <init>(...);
}
-keepclassmembers class * {
    @androidx.room.* <fields>;
}
-dontwarn androidx.room.paging.**

# ── Hilt ───────────────────────────────────────────────────────────────────────
-keepclasseswithmembers class * {
    @dagger.hilt.android.AndroidEntryPoint <methods>;
}
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# ── Coil ───────────────────────────────────────────────────────────────────────
-dontwarn coil.**
-keep class coil.** { *; }

# ── Firebase ───────────────────────────────────────────────────────────────────
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# ── Glance / Widgets ──────────────────────────────────────────────────────────
-keep class androidx.glance.** { *; }
-dontwarn androidx.glance.**

# ── Compose ────────────────────────────────────────────────────────────────────
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}

# ── App models ─────────────────────────────────────────────────────────────────
-keep class com.exapps.mangaworld.domain.model.** { *; }
-keep class com.exapps.mangaworld.core.data.remote.scraper.** { *; }

# ── Firestore-serialized data classes (toObject()/toMap() must survive R8) ─────
# Entity POJOs must NEVER be renamed: release builds once stored favorites /
# history / annotations under obfuscated single-letter keys (no readingStatus),
# invisible to every named-field query. Pushes now use explicit maps, but the
# keep stands as a second net for toObject() pulls and any future POJO write.
-keep class com.exapps.mangaworld.core.data.local.entity.** { *; }
-keep class com.exapps.mangaworld.core.data.local.SyncTombstone { *; }
-keep class com.exapps.mangaworld.core.data.AchievementManager$* { *; }

# ── General ────────────────────────────────────────────────────────────────────
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-optimizationpasses 5
-dontusemixedcaseclassnames
-verbose
