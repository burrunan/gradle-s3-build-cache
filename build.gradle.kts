import com.github.vlsi.gradle.crlf.CrLfSpec
import com.github.vlsi.gradle.crlf.LineEndings
import com.github.vlsi.gradle.dsl.configureEach
import com.github.vlsi.gradle.properties.dsl.props
import com.github.vlsi.gradle.publishing.dsl.simplifyXml
import com.github.vlsi.gradle.publishing.dsl.versionFromResolution
import org.gradle.api.tasks.wrapper.Wrapper.DistributionType
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    `maven-publish`
    `kotlin-dsl`
    id("com.gradle.plugin-publish") version "0.12.0"
    id("com.github.vlsi.crlf") version "1.70"
    id("com.github.vlsi.gradle-extensions") version "1.70"
    id("com.github.vlsi.stage-vote-release") version "1.70"
}

repositories {
    mavenCentral()
}

val String.v: String get() = rootProject.extra["$this.version"] as String

val buildVersion = "current".v + releaseParams.snapshotSuffix

println("Building gradle-s3-build-cache $buildVersion")

val enableGradleMetadata by props()
val autostyleSelf by props()
val skipAutostyle by props()
val skipJavadoc by props()

releaseParams {
    tlp.set("gradle-s3-build-cache")
    organizationName.set("burrunan")
    componentName.set("gradle-s3-build-cache")
    prefixForProperties.set("gh")
    svnDistEnabled.set(false)
    sitePreviewEnabled.set(false)
    nexus {
        mavenCentral()
        // https://github.com/marcphilipp/nexus-publish-plugin/issues/35
        packageGroup.set("com.github.burrunan")
    }
    voteText.set {
        """
        ${it.componentName} v${it.version}-rc${it.rc} is ready for preview.

        Git SHA: ${it.gitSha}
        Staging repository: ${it.nexusRepositoryUri}
        """.trimIndent()
    }
}

kotlinDslPluginOptions {
    experimentalWarning.set(false)
}

dependencies {
    implementation(platform("software.amazon.awssdk:bom:2.17.106"))
    // Needed to automatically enable AWS SSO login
    implementation("software.amazon.awssdk:sso")
    implementation("software.amazon.awssdk:s3") {
        // We do not use netty client so far
        exclude("software.amazon.awssdk", "netty-nio-client")
    }

    testImplementation(platform("org.junit:junit-bom:5.7.1"))
    testImplementation("org.junit.jupiter:junit-jupiter-api")
    testImplementation("org.junit.jupiter:junit-jupiter-params")
    testImplementation("com.adobe.testing:s3mock-junit5:2.1.22") {
        // Gradle has its own logging
        exclude("ch.qos.logback", "logback-classic")
        exclude("org.apache.logging.log4j", "log4j-to-slf4j")
        exclude("org.slf4j", "jul-to-slf4j")
    }
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
}

gradlePlugin {
    plugins {
        create("s3BuildCache") {
            id = "com.github.burrunan.s3-build-cache"
            implementationClass = "com.github.burrunan.s3cache.AwsS3Plugin"
        }
    }
}

pluginBundle {
    website = "https://github.com/burrunan/gradle-s3-build-cache"
    vcsUrl = "https://github.com/burrunan/gradle-s3-build-cache"
    description = "An AWS S3 build cache implementation"
    tags = listOf("build-cache", "s3")

    (plugins) {
        "s3BuildCache" {
            id = "com.github.burrunan.s3-build-cache"
            displayName = "AWS S3 build cache"
        }
    }
}

tasks.wrapper {
    gradleVersion = "6.8.3"
    distributionType = DistributionType.BIN
}

allprojects {
    group = "com.github.burrunan.s3cache"
    version = buildVersion

    tasks.configureEach<AbstractArchiveTask> {
        // Ensure builds are reproducible
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
        dirMode = "775".toInt(8)
        fileMode = "664".toInt(8)
    }

    plugins.withId("java") {
        java {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
            withSourcesJar()
            if (!skipJavadoc) {
                withJavadocJar()
            }
        }

        apply(plugin = "maven-publish")

        if (!enableGradleMetadata) {
            tasks.configureEach<GenerateModuleMetadata> {
                enabled = false
            }
        }

        tasks {
            configureEach<JavaCompile> {
                options.encoding = "UTF-8"
            }

            afterEvaluate {
                // Add default license/notice when missing (e.g. see :src:config that overrides LICENSE)
                configureEach<Jar> {
                    CrLfSpec(LineEndings.LF).run {
                        into("META-INF") {
                            filteringCharset = "UTF-8"
                            duplicatesStrategy = DuplicatesStrategy.EXCLUDE
                            textFrom("$rootDir/LICENSE")
                            textFrom("$rootDir/NOTICE")
                        }
                    }
                }
            }

            configureEach<Jar> {
                manifest {
                    attributes["Bundle-License"] = "Apache-2.0"
                    attributes["Implementation-Title"] = "Gradle S3 Build Cache"
                    attributes["Implementation-Version"] = project.version
                    attributes["Specification-Vendor"] = "Gradle S3 Build Cache"
                    attributes["Specification-Version"] = project.version
                    attributes["Specification-Title"] = "Gradle S3 Build Cache"
                    attributes["Implementation-Vendor"] = "Gradle S3 Build Cache"
                    attributes["Implementation-Vendor-Id"] = "com.github.burrunan.s3cache"
                }
            }

            configureEach<Test> {
                useJUnitPlatform()
                // Keystore configuration for S3Mock server
                systemProperty("server.ssl.key-store", "classpath:test_keystore.jks")
                systemProperty("server.ssl.key-store-password", "password")
                systemProperty("server.ssl.key-alias", "selfsigned")
                systemProperty("server.ssl.key-password", "password")
                // Pass the property to tests
                fun passProperty(name: String, default: String? = null) {
                    val value = System.getProperty(name) ?: default
                    value?.let { systemProperty(name, it) }
                }
                passProperty("junit.jupiter.execution.parallel.enabled", "true")
                passProperty("junit.jupiter.execution.parallel.mode.default", "concurrent")
                passProperty("junit.jupiter.execution.timeout.default", "5 m")
            }
        }

        tasks.configureEach<KotlinCompile> {
            kotlinOptions {
                jvmTarget = "1.8"
            }
        }

        configure<PublishingExtension> {
            publications {
                if (project.path != ":") {
                    create<MavenPublication>(project.name) {
                        artifactId = project.name
                        version = rootProject.version.toString()
                        description = project.description
                        from(project.components.get("java"))
                    }
                }
                withType<MavenPublication> {
                    // if (!skipJavadoc) {
                    // Eager task creation is required due to
                    // https://github.com/gradle/gradle/issues/6246
                    //  artifact(sourcesJar.get())
                    //  artifact(javadocJar.get())
                    // }

                    // Use the resolved versions in pom.xml
                    // Gradle might have different resolution rules, so we set the versions
                    // that were used in Gradle build/test.
                    versionFromResolution()
                    pom {
                        simplifyXml()
                        // afterEvaluate is a workaround to add entries to plugin marker pom
                        afterEvaluate {
                            this@pom.name.set(
                                (project.findProperty("artifact.name") as? String)
                                    ?: "Gradle S3 Build Cache ${project.name.capitalize()}"
                            )
                            this@pom.description.set(
                                project.description
                                    ?: "Gradle S3 Build Cache ${project.name.capitalize()}"
                            )
                        }
                        developers {
                            developer {
                                id.set("vlsi")
                                name.set("Vladimir Sitnikov")
                                email.set("sitnikov.vladimir@gmail.com")
                            }
                        }
                        inceptionYear.set("2020")
                        url.set("https://github.com/burrunan/gradle-s3-build-cache")
                        licenses {
                            license {
                                name.set("The Apache License, Version 2.0")
                                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                                comments.set("A business-friendly OSS license")
                                distribution.set("repo")
                            }
                        }
                        issueManagement {
                            system.set("GitHub")
                            url.set("https://github.com/burrunan/gradle-s3-build-cache/issues")
                        }
                        scm {
                            connection.set("scm:git:https://github.com/burrunan/gradle-s3-build-cache.git")
                            developerConnection.set("scm:git:https://github.com/burrunan/gradle-s3-build-cache.git")
                            url.set("https://github.com/burrunan/gradle-s3-build-cache")
                            tag.set("HEAD")
                        }
                    }
                }
            }
        }
    }
}
