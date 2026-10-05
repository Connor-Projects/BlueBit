plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        }
    }
    jvm("desktop") {
        compilerOptions {
            jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(libs.guava)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
        }

        androidMain.dependencies {
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.appcompat)
            implementation(libs.androidx.activity.compose)
            implementation(libs.material)
            implementation(libs.ktor.client.okhttp)
            implementation("com.topstep.wearkit:sdk-base:3.0.2.4")
            implementation("com.topstep.wearkit:sdk-fitcloud:3.0.2.4") {
                exclude(group = "com.topstep.wearkit", module = "ext-realtek-bbpro")
                exclude(group = "com.topstep.wearkit", module = "ext-realtek-file")
            }
            implementation("io.reactivex.rxjava3:rxjava:3.1.5")
            implementation("io.reactivex.rxjava3:rxandroid:3.0.2")
            implementation("com.polidea.rxandroidble3:rxandroidble:1.17.2")
            implementation("com.jakewharton.timber:timber:5.0.1")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-rx3:1.8.1")
        }

        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.ktor.client.java)
            }
        }

        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test-junit5"))
                implementation(libs.junit.jupiter.engine)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
            }
        }
    }
}

android {
    namespace = "bluebit.app"
    compileSdk = 35
    buildToolsVersion = "36.0.0"

    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    sourceSets["main"].res.srcDirs("src/androidMain/res")
    sourceSets["main"].resources.srcDirs("src/commonMain/resources")

    defaultConfig {
        applicationId = "bluebit.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

compose.desktop {
    application {
        mainClass = "bluebit.BlueBitAppKt"
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
