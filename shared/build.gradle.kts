plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    @Suppress("UnstableApiUsage")
    android {
        namespace = "net.pocketmap.shared"
        compileSdk = 37
        minSdk = 26
        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 }
            }
        }
    }
    jvm() // Run shared tests on Linux without requiring an emulator or Xcode.
    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material)
            implementation(compose.ui)
        }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
