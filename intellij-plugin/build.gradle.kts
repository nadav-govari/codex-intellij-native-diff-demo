plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "dev.codex"
version = "0.2.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        local("/Applications/IntelliJ IDEA.app")
        bundledPlugin("com.intellij.mcpServer")
        pluginVerifier()
    }

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

intellijPlatform {
    pluginConfiguration {
        name = "Codex Native Diff"
        version = project.version.toString()
        ideaVersion {
            sinceBuild = "262"
            untilBuild = "262.*"
        }
    }
    pluginVerification {
        ides {
            local(file("/Applications/IntelliJ IDEA.app"))
        }
    }
    buildSearchableOptions = false
}

tasks {
    test {
        useJUnitPlatform()
    }
}
