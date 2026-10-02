package dev.agentle.buildlogic.android

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

/** SDK levels, Java/Kotlin targets, lint, Robolectric-ready unit tests. Shared by app and library conventions. */
internal fun Project.configureAndroidCommon(extension: CommonExtension) {
    extension.apply {
        compileSdk = libs.intVersion("compileSdk")
        compileSdkMinor = libs.intVersion("compileSdkMinor")
        defaultConfig.minSdk = libs.intVersion("minSdk")
        defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        compileOptions.sourceCompatibility = JavaVersion.VERSION_17
        compileOptions.targetCompatibility = JavaVersion.VERSION_17
        testOptions.unitTests.isIncludeAndroidResources = true
        testOptions.animationsDisabled = true
        lint.abortOnError = true
        lint.checkDependencies = false
        lint.warningsAsErrors = false
        lint.xmlReport = true
        lint.textReport = true
        lint.sarifReport = true
        val lintConfig = rootProject.file("config/lint/lint.xml")
        if (lintConfig.exists()) lint.lintConfig = lintConfig
        packaging.resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }

    extensions.configure<KotlinAndroidProjectExtension> {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs.addAll("-Xjsr305=strict", "-Xconsistent-data-class-copy-visibility")
            allWarningsAsErrors.set(providers.gradleProperty("agentle.warningsAsErrors").map(String::toBoolean).orElse(false))
        }
    }

    dependencies {
        add("testImplementation", libs.lib("junit4"))
        add("testImplementation", libs.lib("robolectric"))
        add("testImplementation", libs.lib("androidx-test-core"))
        add("testImplementation", libs.lib("androidx-test-ext-junit"))
        add("testImplementation", libs.lib("truth"))
        add("testImplementation", libs.lib("kotlinx-coroutines-test"))
        add("testImplementation", libs.lib("turbine"))
        add("androidTestImplementation", libs.lib("androidx-test-runner"))
        add("androidTestImplementation", libs.lib("androidx-test-rules"))
        add("androidTestImplementation", libs.lib("androidx-test-ext-junit"))
        add("androidTestImplementation", libs.lib("truth"))
    }

    tasks.withType<Test>().configureEach {
        // Robolectric SDK matrix: -ProbolectricSdks=29,34,37 (default: the convention's list).
        val sdks = providers.gradleProperty("robolectricSdks").orElse("29,30,31,33,34,35,36,37")
        systemProperty("robolectric.enabledSdks", sdks.get())
        systemProperty("robolectric.alwaysIncludeVariantMarkersInTestName", "true")
        systemProperty("robolectric.logging.enabled", "false")
        systemProperty("user.timezone", "UTC")
        // Robolectric's SDK 36/37 runtimes reach into JDK internals (java.io.FileDescriptor and friends) on JDK 21.
        jvmArgs(
            "--add-opens=java.base/java.io=ALL-UNNAMED",
            "--add-opens=java.base/java.lang=ALL-UNNAMED",
            "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
            "--add-opens=java.base/java.nio=ALL-UNNAMED",
            "--add-opens=java.base/java.util=ALL-UNNAMED",
            "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
            "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
        )
        // Hilt and Compose generate test sources, so modules without tests yet would fail Gradle 9's no-tests check.
        failOnNoDiscoveredTests.set(false)
        maxHeapSize = "2g"
        testLogging {
            events("failed", "skipped")
            exceptionFormat = TestExceptionFormat.FULL
        }
    }
}
