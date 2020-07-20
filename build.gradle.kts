import org.gradle.api.tasks.wrapper.Wrapper.DistributionType
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    `maven-publish`
    `kotlin-dsl`
    id("com.gradle.plugin-publish") version "0.9.7"
    id("org.shipkit.java") version "2.3.4"
    id("org.shipkit.gradle-plugin") version "2.3.4"
}

repositories {
    jcenter()
}

group = "ch.myniva.gradle"

java {
    sourceCompatibility = JavaVersion.VERSION_1_7
    targetCompatibility = JavaVersion.VERSION_1_7
}

tasks.configureEach<KotlinCompile> {
    kotlinOptions {
        jvmTarget = "1.6"
    }
}

kotlinDslPluginOptions {
    experimentalWarning.set(false)
}

dependencies {
    implementation("com.amazonaws:aws-java-sdk-s3:1.11.751")

    testImplementation("junit:junit:4.13")
    testImplementation("org.mockito:mockito-core:2.28.2")
}

gradlePlugin {
    plugins {
        create("s3BuildCache") {
            id = "ch.myniva.s3-build-cache"
            implementationClass = "ch.myniva.gradle.caching.s3.AwsS3Plugin"
        }
    }
}

pluginBundle {
    website = "https://github.com/myniva/gradle-s3-build-cache"
    vcsUrl = "https://github.com/myniva/gradle-s3-build-cache"
    description = "An AWS S3 build cache implementation"
    tags = listOf("build-cache")

    plugins {
        named("s3BuildCache") {
            id = "ch.myniva.s3-build-cache"
            displayName = "AWS S3 build cache"
        }
    }
}

tasks.wrapper {
    gradleVersion = "5.6.4"
    distributionType = DistributionType.ALL
}
