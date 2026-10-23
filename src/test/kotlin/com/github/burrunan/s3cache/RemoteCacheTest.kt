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
import com.github.burrunan.s3cache.internal.CacheEntryMetadata
import org.gradle.api.JavaVersion
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.TaskOutcome
import org.gradle.util.GradleVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.fail
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.MethodSource
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.ObjectIdentifier

@Execution(ExecutionMode.SAME_THREAD)
class RemoteCacheTest : BaseGradleTest() {
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
            .withParameter("com.adobe.testing.s3mock.domain.validKmsKeys", "arn:aws:kms:us-east-1:47110815:key/972393be-674f-4bdc-87ff-ea1b2588a1c6")
            .withInitialBuckets(BUCKET_NAME)
            .build()

        @JvmStatic
        private fun gradleVersionAndSettings(): Iterable<Arguments> {
            if (!isCI) {
                // Use only the minimum supported Gradle version to make the test faster
                return listOf(arguments("8.10.2", ConfigurationCache.ON))
            }
            return mutableListOf<Arguments>().apply {
                if (JavaVersion.current() <= JavaVersion.VERSION_1_8) {
                    add(arguments("4.1", ConfigurationCache.OFF))
                    add(arguments("4.4.1", ConfigurationCache.OFF))
                    add(arguments("4.10.2", ConfigurationCache.OFF))
                }
                // Java 11 requires Gradle 5.0+
                if (JavaVersion.current() <= JavaVersion.VERSION_11) {
                    addAll(
                        listOf(
                            arguments("5.6.2", ConfigurationCache.OFF),
                            arguments("5.4.1", ConfigurationCache.OFF),
                            arguments("6.0", ConfigurationCache.OFF),
                            arguments("6.5", ConfigurationCache.OFF),
                            arguments("7.0", ConfigurationCache.OFF),
                        )
                    )
                }
                // Java 17 requires Gradle 7.3+
                if (JavaVersion.current() <= JavaVersion.VERSION_17) {
                    add(arguments("7.3.3", ConfigurationCache.OFF))
                    add(arguments("7.4.2", ConfigurationCache.OFF))
                    // Configuration cache supports custom caches since 7.5 only: https://github.com/gradle/gradle/issues/14874
                    add(arguments("7.5", ConfigurationCache.ON))
                    add(arguments("7.6.3", ConfigurationCache.ON))
                    add(arguments("8.0.2", ConfigurationCache.ON))
                    add(arguments("8.1", ConfigurationCache.ON))
                    add(arguments("9.4.1", ConfigurationCache.ON))
                }
                // Java 21 requires Gradle 8.5+
                if (JavaVersion.current() <= JavaVersion.VERSION_21) {
                    add(arguments("9.4.1", ConfigurationCache.ON))
                    add(arguments("8.14.2", ConfigurationCache.ON))
                    add(arguments("8.10.2", ConfigurationCache.ON))
                    add(arguments("8.5", ConfigurationCache.ON))
                }
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
                    endpoint = 'localhost:${mockApp.port}'
                    // See https://github.com/adobe/S3Mock/issues/880
                    forcePathStyle = true
                    push = true
                    credentialsProvider = AnonymousCredentialsProvider.create()
                    s3configuration {
                      accelerateModeEnabled(false)
                    }
                }
            }
        """.trimIndent()
        )
    }

    @ParameterizedTest
    @MethodSource("gradleVersionAndSettings")
    fun cacheStoreWorks(gradleVersion: String, configurationCache: ConfigurationCache) {
        val outputFile = "build/out.txt"
        enableConfigurationCache(gradleVersion, configurationCache)

        val destinationProp = destinationProperty(gradleVersion)

        projectDir.resolve("build.gradle").write(
            """
            tasks.create('props', WriteProperties) {
              $destinationProp = file("$outputFile")
              property("hello", "world")
            }
            tasks.create('props2', WriteProperties) {
              $destinationProp = file("${outputFile}2")
              property("hello", "world2")
            }
        """.trimIndent()
        )
        val result = prepare(gradleVersion, "props", "-i", "-s").build()
        if (isCI) {
            println(result.output)
        }
        assertEquals(TaskOutcome.SUCCESS, result.task(":props")?.outcome) {
            "first execution => no cache available,"
        }
        // Delete output to force task re-execution
        projectDir.resolve(outputFile).toFile().delete()
        val result2 = prepare(gradleVersion, "props", "props2", "-i")
            .withEnvironment(
                mapOf(
                    "S3_BUILD_CACHE_ACCESS_KEY_ID" to "access_key 1",
                    "S3_BUILD_CACHE_SECRET_KEY" to "secret_key 1",
                    "S3_BUILD_CACHE_SESSION_TOKEN" to "session_token 1",
                    "S3_BUILD_CACHE_PROFILE" to "cache_profile 1",
                )
            )
            .build()
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
            // The configuration cache should be reused even though the environment variables change
            val result3 = prepare(gradleVersion, "props", "props2", "-i", "-s")
                .withEnvironment(
                    mapOf(
                        "S3_BUILD_CACHE_ACCESS_KEY_ID" to "access_key 2",
                        "S3_BUILD_CACHE_SECRET_KEY" to "secret_key 2",
                        "S3_BUILD_CACHE_SESSION_TOKEN" to "session_token 2",
                        "S3_BUILD_CACHE_PROFILE" to "cache_profile 2",
                    )
                )
                .build()
            if (isCI) {
                println(result3.output)
            }
            if (!result3.output.contains("Reusing configuration cache.")) {
                fail("Configuration cache was not reused")
            }
            assertEquals(TaskOutcome.FROM_CACHE, result3.task(":props")?.outcome) {
                "second execution => task should be resolved from cache"
            }
        }
    }

    /**
     * A cache hit reports the execution time it saved over fetching the item from S3.
     *
     * The estimate used to be computed by a `TaskExecutionListener`, which Gradle rejects under the
     * configuration cache, so such a build printed `S3 cache no impact` however much the cache saved
     * it (issue #60).
     */
    @ParameterizedTest
    @MethodSource("gradleVersionAndSettings")
    fun aCacheHitReportsTheExecutionTimeItSaved(
        gradleVersion: String,
        configurationCache: ConfigurationCache
    ) {
        enableConfigurationCache(gradleVersion, configurationCache)
        projectDir.resolve("build.gradle").write(
            """
            tasks.create('slow', WriteProperties) {
              ${destinationProperty(gradleVersion)} = file("build/slow.txt")
              property("hello", "world")
              doLast {
                // The saved time is the recorded execution time less the time S3Mock took to serve
                // the item, so the task has to run long enough for the difference to keep its sign
                Thread.sleep(1000)
              }
            }
            """.trimIndent()
        )
        prepare(gradleVersion, "slow", "-i").build()
        discardLocalBuildState()

        val fromCache = prepare(gradleVersion, "slow", "-i").build()

        assertEquals(TaskOutcome.FROM_CACHE, fromCache.task(":slow")?.outcome) {
            "the second build should load :slow from the S3 cache"
        }
        assertEquals(
            "S3 cache <time> saved on hits, hits: 1, elapsed: <time>, received: <size>",
            fromCache.cacheReadSummary(),
            "cache read summary of the second build"
        )
    }

    /**
     * A lookup that finds nothing reports the time it spent looking.
     *
     * Misses went unreported alongside hits under the configuration cache (issue #60).
     */
    @ParameterizedTest
    @MethodSource("gradleVersionAndSettings")
    fun aCacheMissReportsTheTimeItWasted(
        gradleVersion: String,
        configurationCache: ConfigurationCache
    ) {
        enableConfigurationCache(gradleVersion, configurationCache)
        val destinationProp = destinationProperty(gradleVersion)
        projectDir.resolve("build.gradle").write(
            """
            // Three lookups, so that the time they take does not round down to zero milliseconds,
            // which the summary would report as `no impact` rather than as time wasted
            (1..3).each { i ->
              tasks.create("props${'$'}i", WriteProperties) {
                $destinationProp = file("build/out${'$'}{i}.txt")
                property("hello", "world${'$'}i")
              }
            }
            """.trimIndent()
        )

        val emptyCache = prepare(gradleVersion, "props1", "props2", "props3", "-i").build()

        assertEquals(TaskOutcome.SUCCESS, emptyCache.task(":props1")?.outcome) {
            "an empty cache leaves :props1 to execute"
        }
        assertEquals(
            "S3 cache <time> wasted on misses, reads: 3, elapsed: <time>",
            emptyCache.cacheReadSummary(),
            "cache read summary of a build against an empty cache"
        )
    }

    /**
     * A hit whose cache item records no execution time is counted apart and left out of the estimate.
     *
     * Such an item used to contribute `0 - taskDuration` to the savings, which reported a cache hit as
     * wasted time, and the estimate gave no sign that it covered fewer hits than it said.
     */
    @ParameterizedTest
    @MethodSource("gradleVersionAndSettings")
    fun aHitWithoutAnExecutionTimeIsReportedRatherThanEstimated(
        gradleVersion: String,
        configurationCache: ConfigurationCache,
        s3Client: S3Client
    ) {
        enableConfigurationCache(gradleVersion, configurationCache)
        projectDir.resolve("build.gradle").write(
            """
            tasks.create('props', WriteProperties) {
              ${destinationProperty(gradleVersion)} = file("build/out.txt")
              property("hello", "world")
            }
            """.trimIndent()
        )
        prepare(gradleVersion, "props", "-i").build()
        stripUserMetadataFromTheStoredItem(s3Client)
        discardLocalBuildState()

        val fromCache = prepare(gradleVersion, "props", "-i").build()

        assertEquals(TaskOutcome.FROM_CACHE, fromCache.task(":props")?.outcome) {
            "the second build should load :props from the S3 cache"
        }
        assertEquals(
            "S3 cache savings not measured, hits: 1, hits without executionTime: 1, " +
                    "elapsed: <time>, received: <size>",
            fromCache.cacheReadSummary(),
            "cache read summary of a build whose cache item records no execution time"
        )
    }

    /**
     * S3Mock lower-cases user metadata keys as S3 does, so this pins the casing MetadataReaderTest
     * can only assume. If a later S3Mock stopped lower-casing, the test would pass and guard nothing.
     */
    @Test
    fun metadataSurvivesTheRoundTripThroughS3(s3Client: S3Client) {
        val stored = CacheEntryMetadata(
            buildInvocationId = "m6e4sb4dmrc5zdnykb5v465pry",
            identity = ":validatePlugins",
            executionTime = 17L,
            operatingSystem = "Mac OS X",
            gradleVersion = "6.3"
        )
        s3Client.putObject(
            { it.bucket(BUCKET_NAME).key("metadata-round-trip").metadata(stored.toMap()) },
            RequestBody.fromString("cache entry")
        )
        val loaded = s3Client.getObject { it.bucket(BUCKET_NAME).key("metadata-round-trip") }
            .use { CacheEntryMetadata(it.response().metadata()) }
        assertEquals(stored.toMap(), loaded.toMap())
    }

    /**
     * Replaces the single stored cache item with a copy that carries no S3 user metadata.
     *
     * The plugin reads the execution time out of that metadata, so a copy without it stands for an
     * item some other tool wrote, or one this plugin stored while it could not read Gradle's own
     * metadata out of the archive.
     */
    private fun stripUserMetadataFromTheStoredItem(s3Client: S3Client) {
        val stored = s3Client.listObjectsV2 { it.bucket(BUCKET_NAME) }.contents().single()
        val body = s3Client.getObjectAsBytes { it.bucket(BUCKET_NAME).key(stored.key()) }.asByteArray()
        s3Client.putObject({ it.bucket(BUCKET_NAME).key(stored.key()) }, RequestBody.fromBytes(body))
    }

    /**
     * Deletes the build output and the task history of the project, so that the next build has to ask
     * the build cache for every task.
     *
     * Deleting the output alone leaves the task history in place, and a daemon whose virtual file
     * system has not caught up with the deletion then reports the task up to date instead of loading
     * it from the cache.
     */
    private fun discardLocalBuildState() {
        projectDir.resolve("build").toFile().deleteRecursively()
        projectDir.resolve(".gradle").toFile().deleteRecursively()
    }

    /**
     * Returns the cache read summary line of the build, with every duration and byte count replaced
     * by a placeholder.
     *
     * The figures depend on how fast the machine served the build, so a test asserts the shape of
     * the line instead. Where the build printed no such line, or more than one, the returned text
     * says so rather than failing here, so that the assertion reports it.
     */
    private fun BuildResult.cacheReadSummary(): String {
        val summaries = output.lines()
            .map { it.trim() }
            .filter { it.startsWith("S3 cache ") && !it.startsWith("S3 cache writes: ") }
            .map {
                it.replace(Regex("""\d\d:\d\d:\d\d|\d\d:\d\d|\d+ms|\d+s"""), "<time>")
                    .replace(Regex("""\d+ [KMG]iB|\d+ B"""), "<size>")
            }
        return when (summaries.size) {
            1 -> summaries[0]
            0 -> "(the build printed no S3 cache read summary)"
            else -> summaries.joinToString(" / ", prefix = "(${summaries.size} summaries) ")
        }
    }

    /**
     * Returns the name of the `WriteProperties` property that names the file to write.
     *
     * Gradle 9.0 renamed `outputFile` to `destinationFile`.
     */
    private fun destinationProperty(gradleVersion: String) =
        if (GradleVersion.version(gradleVersion) >= GradleVersion.version("9.0")) {
            "destinationFile"
        } else {
            "outputFile"
        }

    private fun enableConfigurationCache(
        gradleVersion: String,
        configurationCache: ConfigurationCache
    ) {
        if (configurationCache != ConfigurationCache.ON) {
            return
        }
        if (GradleVersion.version(gradleVersion) < GradleVersion.version("7.0")) {
            fail("Gradle version $gradleVersion does not support configuration cache")
        }
        val propertyName = if (GradleVersion.version(gradleVersion) >= GradleVersion.version("8.1")) {
            // https://docs.gradle.org/8.1/userguide/upgrading_version_8.html#configuration_caching_options_renamed
            "org.gradle.configuration-cache"
        } else {
            "org.gradle.unsafe.configuration-cache"
        }
        // Gradle 6.5 expects values ON, OFF, WARN, so we add the option for 7.0 only
        projectDir.resolve("gradle.properties").toFile().appendText(
            "\n$propertyName=true\n"
        )
    }
}
