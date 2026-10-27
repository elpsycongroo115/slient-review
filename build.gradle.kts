plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "dev.silentreview"
val compatibility2024 = providers.gradleProperty("compatibility2024").map(String::toBoolean).orElse(false).get()
val localPlatformPath = providers.gradleProperty("localPlatformPath").orNull
val localBuildBranch = localPlatformPath?.let {
    Regex("\"buildNumber\"\\s*:\\s*\"(\\d+)\\.")
        .find(file("$it/product-info.json").readText())?.groupValues?.get(1)?.toInt()
        ?: error("Cannot read the local IntelliJ SDK build number")
}
val minimumBuild = if (compatibility2024) 241 else localBuildBranch ?: 243
require(minimumBuild in 241..243) { "This plugin is currently validated for IntelliJ 2024 SDKs only" }
require(compatibility2024 || minimumBuild >= 242) { "The standard variant requires a Java 21 based IntelliJ 2024.2+ SDK" }
version = if (compatibility2024) "1.1.0-2024" else "1.1.0"
if (compatibility2024) layout.buildDirectory.set(layout.projectDirectory.dir("build-2024"))

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        val localPath = localPlatformPath
        if (compatibility2024 && localPath != null) {
            val productInfo = file("$localPath/product-info.json").readText()
            require(Regex("\"buildNumber\"\\s*:\\s*\"241\\.").containsMatchIn(productInfo)) {
                "The 2024 compatibility variant requires a 2024.1 SDK. Omit localPlatformPath to download the pinned SDK."
            }
        }
        if (localPath != null) local(localPath) else intellijIdeaCommunity(if (compatibility2024) "2024.1.7" else "2024.3")
        bundledPlugin("Git4Idea")
    }
    compileOnly("com.google.code.gson:gson:2.11.0")
    testImplementation("com.google.code.gson:gson:2.11.0")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("junit:junit:4.13.2")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    sourceCompatibility = if (compatibility2024) JavaVersion.VERSION_17 else JavaVersion.VERSION_21
    targetCompatibility = sourceCompatibility
}
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(if (compatibility2024) 17 else 21)
}
tasks.test { useJUnitPlatform() }

intellijPlatform {
    pluginConfiguration {
        name = "Silent Review"
        ideaVersion { sinceBuild = minimumBuild.toString(); untilBuild = "243.*" }
    }
}
