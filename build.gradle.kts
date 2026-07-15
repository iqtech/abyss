plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.maven.publish) apply false
}

allprojects {
    group   = "pl.iqtech.abyss"
    version = "0.30.2"
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.kotlin.plugin.serialization")
    apply(plugin = "com.vanniktech.maven.publish")

    configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
        publishToMavenCentral()   // Central Portal (central.sonatype.com)
        signAllPublications()
        pom {
            name.set(project.name)
            description.set("Abyss — distributed graph over Hazelcast with pluggable durable stores")
            url.set("https://github.com/iqtech/abyss")
            licenses {
                license {
                    name.set("The Apache License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                }
            }
            developers {
                developer {
                    id.set("cane")
                    name.set("cane")
                    email.set("cane@iqtech.pl")
                }
            }
            scm {
                url.set("https://github.com/iqtech/abyss")
                connection.set("scm:git:git://github.com/iqtech/abyss.git")
                developerConnection.set("scm:git:ssh://git@github.com/iqtech/abyss.git")
            }
        }
    }

    repositories {
        mavenCentral()
    }

    dependencies {
        "implementation"(rootProject.libs.kotlinx.coroutines)
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            optIn.addAll("kotlin.uuid.ExperimentalUuidApi", "kotlin.time.ExperimentalTime")
        }
    }

    configure<org.gradle.api.plugins.JavaPluginExtension> {
        toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
    }
}
