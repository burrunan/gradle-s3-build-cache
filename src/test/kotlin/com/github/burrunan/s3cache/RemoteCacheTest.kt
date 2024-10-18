/*
 * Copyright 2020-2021 Vladimir Sitnikov <sitnikov.vladimir@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package com.github.burrunan.s3cache

import com.adobe.testing.s3mock.S3MockApplication
import org.gradle.api.JavaVersion
import org.gradle.testkit.runner.TaskOutcome
import org.gradle.util.GradleVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.MethodSource
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.ObjectIdentifier

@Execution(ExecutionMode.SAME_THREAD)
class RemoteCacheTest : BaseGradleTest() {
    var mockAppPort: Int = 0

    enum class ConfigurationCache {
        ON, OFF
    }

    companion object {
        const val BUCKET_NAME = "test-bucket"

        val isCI = System.getenv().containsKey("CI") || System.getProperties().containsKey("CI")

        init {
            // See https://github.com/adobe/S3Mock/issues/230
            System.setProperty("server.ssl.key-store", "classpath:test_keystore.jks")
        }

        @JvmField
        @RegisterExtension
        val s3mock = S3MockBuilder()
            .withParameter("spring.main.banner-mode", "off")
            .withParameter("server.ssl.key-store", "classpath:test_keystore.jks")
            .withParameter("server.ssl.key-alias", "selfsigned")
            .withParameter("server.ssl.key-password", "password")
            .withParameter("server.ssl.key-store-password", "password")
            .withParameter(
                "com.adobe.testing.s3mock.domain.validKmsKeys",
                "arn:aws:kms:us-east-1:47110815:key/972393be-674f-4bdc-87ff-ea1b2588a1c6"
            )
            .withInitialBuckets(BUCKET_NAME)
            .build()

        @JvmStatic
        private fun gradleVersionAndSettings(): Iterable<Arguments> {
            if (!isCI) {
                // Use only the minimum supported Gradle version to make the test faster
                return listOf(arguments("4.1", ConfigurationCache.OFF))
            }
            return mutableListOf<Arguments>().apply {
                if (JavaVersion.current() <= JavaVersion.VERSION_1_8) {
                    add(arguments("4.1", ConfigurationCache.OFF))
                    add(arguments("4.4.1", ConfigurationCache.OFF))
                }
                if (JavaVersion.current() <= JavaVersion.VERSION_12) {
                    addAll(
                        listOf(
                            arguments("5.6.2", ConfigurationCache.OFF),
                            arguments("5.4.1", ConfigurationCache.OFF),
                            arguments("4.10.2", ConfigurationCache.OFF)
                        )
                    )
                }
                add(arguments("6.0", ConfigurationCache.OFF))
                add(arguments("6.5", ConfigurationCache.OFF))
                add(arguments("7.0", ConfigurationCache.OFF))
                add(arguments("7.4.2", ConfigurationCache.OFF))
                // Configuration cache supports custom caches since 7.5 only: https://github.com/gradle/gradle/issues/14874
                add(arguments("7.5", ConfigurationCache.ON))
            }
        }
    }

    @BeforeEach
    fun configureCache(mockApp: S3MockApplication, s3Client: S3Client) {
        val keystore = javaClass.classLoader.getResource("test_keystore.jks")!!.asFile

        // Make sure every test starts with an empty bucket
        val objects = s3Client.listObjectsV2 {
            it.bucket(BUCKET_NAME)
        }.contents()
        if (objects.isNotEmpty()) {
            val ids = objects.map { ObjectIdentifier.builder().key(it.key()).build() }
            s3Client.deleteObjects {
                it.bucket(BUCKET_NAME)
                it.delete { delete -> delete.objects(ids) }
            }
        }

        projectDir.resolve("gradle.properties").write(
            """
            org.gradle.caching=true
            org.gradle.caching.debug=true

            #systemProp.javax.net.debug=all
            systemProp.javax.net.ssl.trustStore=${keystore.absolutePath}
            systemProp.javax.net.ssl.trustStorePassword=password
        """.trimIndent()
        )

        mockAppPort = mockApp.port
    }

    @ParameterizedTest
    @MethodSource("gradleVersionAndSettings")
    fun cacheStoreWorks(gradleVersion: String, configurationCache: ConfigurationCache) {
        @Suppress("DEPRECATION")
        createSettings(
            """
            import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider
            buildCache {
                local {
                    // Only remote cache should be used
                    enabled = false
                }
                remote(com.github.burrunan.s3cache.AwsS3BuildCache) {
                    region = 'eu-west-1'
                    bucket = '$BUCKET_NAME'
                    prefix = 'build-cache/'
                    kmsKeyId = '972393be-674f-4bdc-87ff-ea1b2588a1c6'
                    endpoint = 'localhost:$mockAppPort'
                    // See https://github.com/adobe/S3Mock/issues/880
                    forcePathStyle = true
                    push = true
                    credentialsProvider = AnonymousCredentialsProvider.create()
                }
            }
        """.trimIndent()
        )

        val outputFile = "build/out.txt"
        enableConfigurationCache(gradleVersion, configurationCache)
        projectDir.resolve("build.gradle").write(
            """
            tasks.create('props', WriteProperties) {
              outputFile = file("$outputFile")
              property("hello", "world")
            }
            tasks.create('props2', WriteProperties) {
              outputFile = file("${outputFile}2")
              property("hello", "world2")
            }
        """.trimIndent()
        )
        val result = prepare(gradleVersion, "props", "-i").build()
        if (isCI) {
            println(result.output)
        }
        assertEquals(TaskOutcome.SUCCESS, result.task(":props")?.outcome) {
            "first execution => no cache available,"
        }
        // Delete output to force task re-execution
        projectDir.resolve(outputFile).toFile().delete()
        val result2 = prepare(gradleVersion, "props", "props2", "-i").build()
        if (isCI) {
            println(result2.output)
        }
        assertEquals(TaskOutcome.FROM_CACHE, result2.task(":props")?.outcome) {
            "second execution => task should be resolved from cache"
        }
        // Once more, with configuration cache
        if (configurationCache == ConfigurationCache.ON) {
            // Delete output to force task re-execution
            projectDir.resolve(outputFile).toFile().delete()
            val result3 = prepare(gradleVersion, "props", "props2", "-i").build()
            if (isCI) {
                println(result3.output)
            }
            assertEquals(TaskOutcome.FROM_CACHE, result3.task(":props")?.outcome) {
                "second execution => task should be resolved from cache"
            }
        }
    }

    @Test
    fun should_configuration_cache_compatible_when_aws_environment_values_update() {
        @Suppress("DEPRECATION")
        createSettings(
            """
            import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider
            buildCache {
                local {
                    // Only remote cache should be used
                    enabled = false
                }
                remote(com.github.burrunan.s3cache.AwsS3BuildCache) {
                    region = 'eu-west-1'
                    bucket = '$BUCKET_NAME'
                    prefix = 'build-cache/'
                    kmsKeyId = '972393be-674f-4bdc-87ff-ea1b2588a1c6'
                    endpoint = 'localhost:$mockAppPort'
                    // See https://github.com/adobe/S3Mock/issues/880
                    forcePathStyle = true
                    push = true
                    credentialsProvider = AnonymousCredentialsProvider.create()
                    awsAccessKeyId = providers.environmentVariable("S3_BUILD_CACHE_ACCESS_KEY_ID")
                    awsSecretKey = providers.environmentVariable("S3_BUILD_CACHE_SECRET_KEY")
                    sessionToken = providers.environmentVariable("S3_BUILD_CACHE_SESSION_TOKEN")
                    awsProfile = providers.environmentVariable("S3_BUILD_CACHE_PROFILE")
                }
            }
        """.trimIndent()
        )

        val outputFile = "build/out.txt"
        val version = "7.5" // Configuration cache supports custom caches since 7.5 only: https://github.com/gradle/gradle/issues/14874
        enableConfigurationCache(version, ConfigurationCache.ON)
        projectDir.resolve("build.gradle").write(
            """
            tasks.create('props', WriteProperties) {
              outputFile = file("$outputFile")
              property("hello", "world")
            }
        """.trimIndent()
        )

        val runner = gradleRunner
            .withGradleVersion(version)
            .withProjectDir(projectDir.toFile())
        val result = runner
            .withEnvironment(
                mapOf(
                    "S3_BUILD_CACHE_ACCESS_KEY_ID" to "ABCD",
                    "S3_BUILD_CACHE_SECRET_KEY" to "ABCD",
                    "S3_BUILD_CACHE_SESSION_TOKEN" to "ABCD",
                    "S3_BUILD_CACHE_PROFILE" to "ABCD"
                )
            )
            .withArguments(":props", "--configuration-cache")
            .build()
        println(result.output)
        val result2 = runner
            .withEnvironment(
                mapOf(
                    "S3_BUILD_CACHE_ACCESS_KEY_ID" to "EFGH",
                    "S3_BUILD_CACHE_SECRET_KEY" to "EFGH",
                    "S3_BUILD_CACHE_SESSION_TOKEN" to "EFGH",
                    "S3_BUILD_CACHE_PROFILE" to "EFGH"
                )
            )
            .withArguments(":props", "--configuration-cache")
            .build()
        require(result2.output.contains("Reusing configuration cache.")) {
            result2.output
        }
    }

    private fun enableConfigurationCache(
        gradleVersion: String,
        configurationCache: ConfigurationCache
    ) {
        if (configurationCache != ConfigurationCache.ON) {
            return
        }
        if (GradleVersion.version(gradleVersion) < GradleVersion.version("7.0")) {
            fail<Unit>("Gradle version $gradleVersion does not support configuration cache")
        }
        // Gradle 6.5 expects values ON, OFF, WARN, so we add the option for 7.0 only
        projectDir.resolve("gradle.properties").toFile().appendText(
            "\norg.gradle.unsafe.configuration-cache=true\n"
        )
    }
}
