import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
    
    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }
    
    sourceSets {
        androidMain.dependencies {
            implementation(compose.preview)
            implementation(libs.androidx.activity.compose)
            implementation(libs.ktor.client.android)
            implementation(libs.androidx.media3.exoplayer)
            implementation(libs.androidx.media3.datasource.okhttp)
            implementation(libs.androidx.media3.common)
            implementation(libs.androidx.media3.session) // MediaSession 추가
        }
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            
            implementation(libs.androidx.room.runtime)
            implementation(libs.sqlite.bundled)
            implementation(libs.kotlinx.serialization.json)
            
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.ktor.client.logging)

            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor)
            
            implementation(libs.navigation.compose)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}

android {
    namespace = "com.nas.musicplayer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nas.musicplayer"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

compose.resources {
    packageOfResClass = "com.nas.musicplayer"
}

// iOS 리소스 동기화 시 Xcode 환경변수가 없을 경우 발생하는 오류 해결
tasks.matching { it.name == "syncComposeResourcesForIos" }.configureEach {
    val task = this
    try {
        // Use reflection to access internal task properties
        task.javaClass.getMethod("getXcodeTargetArchs").invoke(task)?.let {
            val p = it as org.gradle.api.provider.ListProperty<*>
            if (!p.isPresent) {
                val archs = System.getenv("ARCHS")?.split(" ") ?: if (System.getProperty("os.arch") == "aarch64") listOf("arm64") else listOf("x86_64")
                @Suppress("UNCHECKED_CAST")
                (p as org.gradle.api.provider.ListProperty<String>).set(archs)
            }
        }
        task.javaClass.getMethod("getXcodeTargetPlatform").invoke(task)?.let {
            val p = it as org.gradle.api.provider.Property<*>
            if (!p.isPresent) {
                val platform = System.getenv("SDK_NAME") ?: "iphonesimulator"
                @Suppress("UNCHECKED_CAST")
                (p as org.gradle.api.provider.Property<String>).set(platform)
            }
        }
        task.javaClass.getMethod("getOutputDir").invoke(task)?.let {
            val p = it as org.gradle.api.provider.Property<*>
            if (!p.isPresent) {
                val buildDir = System.getenv("CONFIGURATION_BUILD_DIR")
                @Suppress("UNCHECKED_CAST")
                val dirProp = p as org.gradle.api.provider.Property<org.gradle.api.file.Directory>
                if (buildDir != null) {
                    dirProp.set(project.layout.projectDirectory.dir(buildDir))
                } else {
                    dirProp.set(project.layout.buildDirectory.dir("compose/iosResources"))
                }
            }
        }
    } catch (e: Exception) {
        // Reflection failed
    }
}

dependencies {
    debugImplementation(compose.uiTooling)
    add("kspAndroid", libs.androidx.room.compiler)
    add("kspIosX64", libs.androidx.room.compiler)
    add("kspIosArm64", libs.androidx.room.compiler)
    add("kspIosSimulatorArm64", libs.androidx.room.compiler)
}
