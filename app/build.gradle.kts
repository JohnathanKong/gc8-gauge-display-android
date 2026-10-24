import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "io.toyboxracing.gc8companion"
    compileSdk = 35
    defaultConfig {
        applicationId = "io.toyboxracing.gc8companion"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core:1.15.0")
}


// Exact approved launcher artwork is committed as a base64-encoded WebP so
// GitHub source updates remain self-contained. Build automatically materializes
// the binary asset; no manual icon installation step is required.
val generateGc8LauncherIcon = tasks.register("generateGc8LauncherIcon") {
    val source = layout.projectDirectory.file("branding/gc8_companion_icon.webp.base64")
    val output = layout.projectDirectory.file("src/main/res/drawable-nodpi/gc8_companion_bitmap.webp")
    inputs.file(source)
    outputs.file(output)
    doLast {
        val destination = output.asFile
        destination.parentFile.mkdirs()
        destination.writeBytes(Base64.getMimeDecoder().decode(source.asFile.readText().trim()))
    }
}
tasks.named("preBuild") { dependsOn(generateGc8LauncherIcon) }
