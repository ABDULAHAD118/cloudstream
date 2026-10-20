import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// Fork-only macOS app. Kept apart from upstream's desktopApp so daily upstream syncs don't conflict.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

// -PcsBuildDir=… builds somewhere else, so tests can run while `run` is using macApp/build (it loads classes from there)
(findProperty("csBuildDir") as String?)?.let { layout.buildDirectory.set(file(it)) }

kotlin {
    jvm {}
    sourceSets {
        all {
            languageSettings {
                // The app itself is allowed to use the library's internal helpers, like upstream's shared module
                optIn("com.lagradost.cloudstream3.InternalAPI")
                optIn("com.lagradost.cloudstream3.Prerelease")
            }
        }
        jvmMain.dependencies {
            implementation(libs.bundles.compose)
            implementation(libs.coil.network.okhttp)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.nicehttp)
            implementation(libs.jackson.module.kotlin) // IMDb info and its cache, with the library's own JSON mapper
            implementation(libs.newpipeextractor) // the library's YouTube support needs a downloader from the app
            implementation(libs.ktor.http) // HlsPlaylistParser's variant URLs, for the download quality picker
            // Material icons (1.7.3 is the last published; the icons are plain vectors, so they work with newer Compose)
            implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
            implementation(compose.desktop.currentOs) {
                // compose.desktop.currentOs imports the wrong material 2, so we exclude it
                exclude(group = "org.jetbrains.compose.material", module = "material")
            }
            // Converts Android DEX extensions (.cs3) to JVM bytecode at install time
            implementation("de.femtopedia.dex2jar:dex-tools:2.4.28")
            // In-app player: Java bindings to libVLC (bundled into the DMG by CI)
            implementation("uk.co.caprica:vlcj:4.11.0")
            implementation(project(":shared"))
            implementation(project(":library"))
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
        }
    }
}

tasks.withType<Test> {
    systemProperty("cloudstream.dataDir", layout.buildDirectory.dir("test-data").get().asFile.path)
    listOf("CS_SMOKE", "CS_VLC_LOG", "CS_PROXY_LOG", "CS_HTTP_LOG").forEach { environment(it, System.getenv(it) ?: "") }
    testLogging { showStandardStreams = true }
}

tasks.withType<JavaExec> {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

compose.desktop {
    application {
        mainClass = "com.lagradost.cloudstream.mac.MainKt"
        jvmArgs("--enable-native-access=ALL-UNNAMED")
        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            packageName = "CloudStream"
            // DMG versions must be MAJOR.MINOR.PATCH with MAJOR > 0
            packageVersion = (findProperty("macAppVersion") as String?) ?: "1.0.0"
            // shortcut: bundles the whole JRE instead of a jlink-minimised one, since extensions can touch any JDK module
            includeAllModules = true
            // CI drops libVLC into resources/macos-<arch>/vlc for the in-app player (see mac-release.yml)
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))
            macOS {
                bundleID = "io.github.abdulahad118.cloudstream"
                iconFile.set(project.file("icon.icns"))
            }
        }
    }
}
