# R8 rules for the release build. Most libraries ship their own consumer rules in their
# jars/AARs (OkHttp, Room, kotlinx.serialization, Compose, AndroidX); these are the extras
# this app relies on, kept explicit so a dependency change can't silently break them.

# --- kotlinx.serialization ---
# SearchQuery (and its enums) is stored as JSON in the presets table, so its serializer must
# survive. The library's bundled rules cover serializer lookup; keep the generated companions
# and $serializer classes of our own models explicitly as well.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,InnerClasses,Signature
-if @kotlinx.serialization.Serializable class app.ao3reader.**
-keepclassmembers class <1> {
    static <1>$Companion Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class app.ao3reader.**$$serializer { *; }
# @SerialName values are string literals in generated code, but enum constant names are still
# used as the default serial names: keep the enums' names for stored presets to stay readable.
-keepclassmembers enum app.ao3reader.ao3.model.** {
    <fields>;
}

# --- Room ---
# Room instantiates the generated AppDatabase_Impl reflectively.
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# --- Jsoup ---
# Jsoup optionally uses re2j for regexes when it's on the classpath; it isn't here.
-dontwarn com.google.re2j.**

# --- OkHttp ---
# Rules ship inside the OkHttp jar (META-INF/proguard/okhttp3.pro); nothing extra needed.
