# DSH Mobile R8 rules for optimized and release builds.

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.labteto.dshmobile.core.wire.dto.** {
    *** Companion;
}
-keepclasseswithmembers class com.labteto.dshmobile.core.wire.dto.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
