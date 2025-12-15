import com.github.vlsi.gradle.crlf.CrLfSpec
import com.github.vlsi.gradle.crlf.LineEndings
import com.github.vlsi.gradle.dsl.configureEach
import com.github.vlsi.gradle.properties.dsl.props
import com.github.vlsi.gradle.publishing.dsl.simplifyXml
import com.github.vlsi.gradle.publishing.dsl.versionFromResolution
import org.gradle.api.tasks.wrapper.Wrapper.DistributionType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import java.time.Duration
import java.util.*

plugins {
    `maven-publish`
    kotlin("jvm")
    `kotlin-dsl`
    id("com.gradle.plugin-publish") version "2.0.0"
    id("com.github.vlsi.crlf") version "3.0.1"
    id("com.github.vlsi.gradle-extensions") version "3.0.1"
    id("com.gradleup.nmcp") version "0.1.5"
    id("signing")
}

repositories {
    mavenCentral()
}

val release by props(default = false)
val useInMemoryPgpKeys by props(default = true)

val String.v: String get() = rootProject.extra["$this.version"] as String

val buildVersion = "current".v + (if (release) "" else "-SNAPSHOT")

println("Building gradle-s3-build-cache $buildVersion")

val enableGradleMetadata by props()
val autostyleSelf by props()
val skipAutostyle by props()
val skipJavadoc by props()
val buildJdk by props.int
val targetJdk by props.int
val testJdk by props.int
val centralPortalPublishingType = props.string("centralPortalPublishingType", default = "AUTOMATIC")
val centralPortalPublishingTimeout by props.long

dependencies {
    constraints {
        implementation("org.apache.httpcomponents:httpclient:4.5.14") {
            because("httpclient 4.5.13 fails to verify *.s3.amazonaws.com certificates, see https://github.com/burrunan/gradle-s3-build-cache/issues/23")
        }
    }
    implementation(platform("software.amazon.awssdk:bom:2.39.6"))
    implementation("software.amazon.awssdk:sso") {
        because("Needed to automatically enable AWS SSO login, see https://stackoverflow.com/a/67824174")
    }
    implementation ("software.amazon.awssdk:ssooidc") {
        because("Needed to automatically enable AWS SSO login, see https://stackoverflow.com/a/67824174")
    }
    implementation("software.amazon.awssdk:s3") {
        // We do not use netty client so far
        exclude("software.amazon.awssdk", "netty-nio-client")
    }
    runtimeOnly("software.amazon.awssdk:sts")

    testImplementation(platform("org.junit:junit-bom:5.14.1"))
    testImplementation("org.junit.jupiter:junit-jupiter-api")
    testImplementation("org.junit.jupiter:junit-jupiter-params")
    testImplementation("com.adobe.testing:s3mock-junit5:4.9.1") {
        // Gradle has its own logging
        exclude("ch.qos.logback", "logback-classic")
        exclude("org.apache.logging.log4j", "log4j-to-slf4j")
        exclude("org.slf4j", "jul-to-slf4j")
    }
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

gradlePlugin {
    website.set("https://github.com/burrunan/gradle-s3-build-cache")
    vcsUrl.set("https://github.com/burrunan/gradle-s3-build-cache")
    plugins {
        create("s3BuildCache") {
            id = "com.github.burrunan.s3-build-cache"
            tags.set(listOf("build-cache", "s3"))
            displayName = "AWS S3 build cache"
            description = "An AWS S3 build cache implementation"
            implementationClass = "com.github.burrunan.s3cache.AwsS3Plugin"
        }
    }
}

tasks.wrapper {
    gradleVersion = "8.14.3"
    distributionType = DistributionType.BIN
}

if (!release) {
    publishing {
        repositories {
            maven {
                name = "centralSnapshots"
                url = uri("https://central.sonatype.com/repository/maven-snapshots")
                credentials(PasswordCredentials::class)
            }
        }
    }
} else {
    signing {
        sign(publishing.publications)
        if (!useInMemoryPgpKeys) {
            useGpgCmd()
        } else {
            val pgpPrivateKey = System.getenv("SIGNING_PGP_PRIVATE_KEY")
            val pgpPassphrase = System.getenv("SIGNING_PGP_PASSPHRASE")
            if (pgpPrivateKey.isNullOrBlank() || pgpPassphrase.isNullOrBlank()) {
                throw IllegalArgumentException("GPP private key (SIGNING_PGP_PRIVATE_KEY) and passphrase (SIGNING_PGP_PASSPHRASE) must be set")
            }
            useInMemoryPgpKeys(
                pgpPrivateKey,
                pgpPassphrase
            )
        }
    }
    nmcp {
        centralPortal {
            username = providers.environmentVariable("CENTRAL_PORTAL_USERNAME")
            password = providers.environmentVariable("CENTRAL_PORTAL_PASSWORD")
            publishingType = centralPortalPublishingType
            verificationTimeout = Duration.ofMinutes(centralPortalPublishingTimeout)
        }
    }
}

allprojects {
    group = "com.github.burrunan.s3cache"
    version = buildVersion

    tasks.configureEach<AbstractArchiveTask> {
        // Ensure builds are reproducible
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
        dirPermissions {
            user {
                read = true
                write = true
                execute = true
            }
            group {
                read = true
                write = true
                execute = true
            }
            other {
                read = true
                execute = true
            }
        }
        filePermissions {
            user {
                read = true
                write = true
            }
            group {
                read = true
                write = true
            }
            other {
                read = true
            }
        }
    }

    plugins.withId("java") {
        java {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(buildJdk))
            }
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
                options.release.set(targetJdk)
            }

            compileTestJava {
                options.release.set(testJdk)
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
                javaLauncher.set(
                    project.javaToolchains.launcherFor {
                        languageVersion.set(JavaLanguageVersion.of(testJdk))
                    }
                )

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

        tasks.configureEach<KotlinJvmCompile> {
            compilerOptions {
                @Suppress("DEPRECATION")
                val kotlinVersion = KotlinVersion.KOTLIN_1_9
                apiVersion = kotlinVersion
                languageVersion = kotlinVersion
            }
        }

        kotlin {
            coreLibrariesVersion = "1.9.20"
        }

        tasks.compileKotlin {
            compilerOptions {
                val jdkRelease = if (targetJdk < 9) "1.8" else targetJdk.toString()
                jvmTarget = JvmTarget.fromTarget(jdkRelease)
                freeCompilerArgs.add("-Xjdk-release=$jdkRelease")
            }
        }

        tasks.compileTestKotlin {
            compilerOptions {
                val jdkRelease = testJdk.toString()
                jvmTarget = JvmTarget.fromTarget(jdkRelease)
                freeCompilerArgs.add("-Xjdk-release=$jdkRelease")
            }
        }

        configure<PublishingExtension> {
            publications {
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
                            val defaultName = "Gradle S3 Build Cache ${project.name.replaceFirstChar {
                                if (it.isLowerCase()) it.titlecase(
                                    Locale.getDefault()
                                ) else it.toString()
                            }}"
                            this@pom.name.set(
                                (project.findProperty("artifact.name") as? String)
                                    ?: defaultName
                            )
                            this@pom.description.set(
                                project.description
                                    ?: defaultName
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
