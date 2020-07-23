import com.github.vlsi.gradle.dsl.configureEach
import org.gradle.api.tasks.wrapper.Wrapper.DistributionType
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    `maven-publish`
    `kotlin-dsl`
    id("com.gradle.plugin-publish") version "0.9.7"
    id("org.shipkit.java") version "2.3.4"
    id("org.shipkit.gradle-plugin") version "2.3.4"
    id("com.github.vlsi.gradle-extensions") version "1.70"
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
    compileOnly("org.apache.ant:ant:1.9.6")

    testImplementation(platform("org.junit:junit-bom:5.7.0-M1"))
    testImplementation("org.junit.jupiter:junit-jupiter-api")
    testImplementation("org.junit.jupiter:junit-jupiter-params")
    testImplementation("com.adobe.testing:s3mock-junit5:2.1.22") {
        // Gradle has its own logging
        exclude("ch.qos.logback", "logback-classic")
        exclude("org.apache.logging.log4j", "log4j-to-slf4j")
        exclude("org.slf4j", "jul-to-slf4j")
    }
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testImplementation("org.apache.ant:ant:1.9.6")
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

tasks.configureEach<Test> {
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
