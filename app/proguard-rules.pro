# R8 is on for release builds (see app/build.gradle). The two rules below are what the zero-reflection
# design actually needs:

# ViewBinding, RecyclerView and the lifecycle VMs are all reached from generated code whose classes
# live in the app package; keep their members so the reflective plumbing Android itself does survives.
-keep class in.opentrack.india.databinding.** { *; }
-keep class in.opentrack.india.ui.*Activity { *; }
-keepclassmembers class in.opentrack.india.ui.**ViewModel { *; }

# The snapshot codec reads map keys that mirror the wire contract; the names are data, not code.
-keepclassmembers class in.opentrack.india.remote.SnapshotCodec$Wire { *; }

# Kotlin stdlib / coroutines hygiene (AGP supplies most of this; kept explicit so a future AGP bump
# cannot silently start shrinking away a coroutine intrinsic).
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# The AndroidX test runner is an unminified *test APK* executing in the release app's
# process. R8 can inline/remove stdlib names that production no longer uses even though
# the runner still links against them by published ABI. Device crash diagnostics confirmed
# kotlin.jvm.internal.Intrinsics, then kotlin.jvm.functions.Function0 were missing in
# successive release sweeps; keeping just the first class causes the next crash. Preserve
# the shared Kotlin runtime API (not the app's classes) for release instrumentation.
-keep class kotlin.** { *; }
-keep interface kotlin.** { *; }
# The runner also uses androidx.tracing.Trace; R8 otherwise drops this transitive name.
-keep class androidx.tracing.** { *; }

# Release diagnostics: keep line numbers for the Play crash report, drop nothing else.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
