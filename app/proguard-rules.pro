# R8 rules for the release build. Compose, WorkManager, OkHttp and kotlinx.serialization ship their own rules.
# These keep the generated serializers of our models.

-keepattributes *Annotation*, InnerClasses, Signature
-dontnote kotlinx.serialization.**

-keepclassmembers @kotlinx.serialization.Serializable class io.github.cadenh.wristrature.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class io.github.cadenh.wristrature.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class io.github.cadenh.wristrature.**$$serializer { *; }

# OkHttp optional platform classes.
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
