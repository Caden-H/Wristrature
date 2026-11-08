import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin/JVM: EPUB import, the block model, reading positions (including the KOReader position
// ladder), the KOReader sync client, the OPDS client and demo data. No Android UI, so every rule runs in
// plain JUnit. XML is read with XmlPullParser, which Android provides itself (kxml2 stands in for tests).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(libs.okhttp)
    compileOnly(libs.xmlpull)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.xmlpull)
    testImplementation(libs.kxml2)
}
