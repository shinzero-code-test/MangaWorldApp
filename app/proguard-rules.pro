# ── Jsoup ──────────────────────────────────────────────────────────────────────
-keep public class org.jsoup.** { *; }

# ── OkHttp ─────────────────────────────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
-keep class okhttp3.** { *; }

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
-keep class com.exapps.mangaworld.core.data.local.SyncTombstone { *; }
-keep class com.exapps.mangaworld.core.data.AchievementManager$* { *; }

# ── General ────────────────────────────────────────────────────────────────────
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-optimizationpasses 5
-dontusemixedcaseclassnames
-verbose
