// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "8.13.2" apply false
    // Kotlin 1.9.22 cannot read metadata produced by Kotlin 2.x compilers,
    // which makes OkHttp 5.x / Coil 3.x / rssparser 6.x fail with
    // "compiled with an incompatible version of Kotlin" — hence 2.4.20.
    // Version matrix (official, kotlinlang.org): KGP 2.4.20 supports Gradle 7.6.3–9.7.0
    // and AGP 8.5.2–9.3.1; this project's Gradle 8.13 + AGP 8.13.2 both fall inside.
    id("org.jetbrains.kotlin.android") version "2.4.20" apply false
    // Since KSP 2.3.0 the versioning is unified (no more -1.0.x suffix);
    // 2.3.10+ adapts to Kotlin 2.4's module naming.
    id("com.google.devtools.ksp") version "2.3.12" apply false
}
