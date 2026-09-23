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

# WorkManager 2.10 creates its generated Room database implementation reflectively.
# AGP 9.4 / R8 can otherwise remove the no-arg constructor while keeping the class,
# causing InitializationProvider to crash before Application.onCreate().
-keep class androidx.work.impl.WorkDatabase_Impl {
    <init>();
}


# HDiffPatch JNI exports Java_com_github_sisong_HPatch_patch; keep the ABI name stable.
-keep class com.github.sisong.HPatch {
    *;
}
