# R8 / ProGuard rules for the `optimized` build type (and for `release` once it is minified).
#
# The rules below are deliberately conservative: everything that is resolved *by name* at
# runtime (Go <-> Java JNI callbacks, WorkManager workers, Room implementations, WebView JS
# bridges) is kept, and the app's own classes are kept unrenamed. What R8 still does — and
# where the speed and size win actually comes from — is remove and optimize the unused parts
# of the libraries (Compose, AndroidX, OkHttp, zxing, kotlin stdlib).

# ---------------------------------------------------------------------------
# Go gateway (gomobile AAR: app/libs/wagateway.aar)
# ---------------------------------------------------------------------------
# The Go side reaches back into Java through JNI by class and method name, so these names are
# part of a binary contract that R8 cannot see: renaming `wagateway.Client`, the listener
# interface, or the method that implements it produces an app that compiles and then dies at
# runtime with NoSuchMethodError.
-keep class wagateway.** { *; }
-keep class * implements wagateway.WaEventListener { *; }

# Native methods and the classes declaring them are looked up by name from the native side.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# ---------------------------------------------------------------------------
# Own code: kept verbatim on purpose
# ---------------------------------------------------------------------------
# Obfuscating com.example.** would hide nothing worth hiding (this is a single-user agent app)
# while risking the JNI callbacks above, the callback names used by the agent loop and the
# reflective entry points of Room/WorkManager — none of which can be exercised on a device from
# CI. Keeping them is the honest trade: libraries are still shrunk and optimized, app code is not
# renamed. Flip this to `-keep class com.example.agent.** { *; }` and shrink the keep-list
# gradually once an optimized build has been smoke-tested on hardware.
-keep class com.example.** { *; }

# Readable stack traces in crash reports (R8 would otherwise reduce them to line 0).
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod,*Annotation*
-renamesourcefileattribute SourceFile

# ---------------------------------------------------------------------------
# AndroidX pieces that are instanced reflectively
# ---------------------------------------------------------------------------
# WorkManager restores workers from the class name stored in its own database (SchedulerWorker).
-keepclassmembers class * extends androidx.work.ListenableWorker {
    public <init>(...);
}
-keep class * extends androidx.work.CoroutineWorker { *; }

# Room looks its generated implementation up by name; the runtime ships rules for this too, the
# line below only makes the dependency explicit.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }

# WebView JavaScript bridges are resolved by name from JavaScript.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ---------------------------------------------------------------------------
# Optional integrations that are absent from the APK on purpose
# ---------------------------------------------------------------------------
# OkHttp (and the TLS stack it probes) reference providers it can live without: the build must
# not fail just because Conscrypt/BouncyCastle/OpenJSSE are not shipped on Android.
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
