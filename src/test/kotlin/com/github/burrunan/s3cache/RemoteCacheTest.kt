/*
 * Copyright 2020 Vladimir Sitnikov <sitnikov.vladimir@gmail.com>
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
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model.DeleteObjectsRequest
import org.gradle.api.JavaVersion
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.MethodSource

@Execution(ExecutionMode.SAME_THREAD)
class RemoteCacheTest: BaseGradleTest() {
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
            .withInitialBuckets(BUCKET_NAME)
            .build()

        @JvmStatic
        private fun gradleVersionAndSettings(): Iterable<Arguments> {
            if (!isCI) {
                // Use only the minimum supported Gradle version to make the test faster
                return listOf(arguments("4.1"))
            }
            return mutableListOf<Arguments>().apply {
                if (JavaVersion.current() <= JavaVersion.VERSION_1_8) {
                    add(arguments("4.1"))
                    add(arguments("4.4.1"))
                }
                if (JavaVersion.current() <= JavaVersion.VERSION_12) {
                    addAll(
                        listOf(
                            arguments("5.6.2"),
                            arguments("5.4.1"),
                            arguments("4.10.2")
                        )
                    )
                }
                add(arguments("6.0"))
                add(arguments("6.5"))
            }
        }
    }

    @BeforeEach
    fun configureCache(mockApp: S3MockApplication, s3Client: AmazonS3) {
        val keystore = javaClass.classLoader.getResource("test_keystore.jks")!!.asFile

        // Make sure every test starts with an empty bucket
        val objects = s3Client.listObjectsV2(BUCKET_NAME).objectSummaries
        if (objects.isNotEmpty()) {
            s3Client.deleteObjects(
                DeleteObjectsRequest(BUCKET_NAME)
                    .withKeys(objects.map {
                        DeleteObjectsRequest.KeyVersion(it.key)
                    })
            )
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

        createSettings(
            """
            buildCache {
                local {
                    // Only remote cache should be used
                    enabled = false
                }
                remote(com.github.burrunan.s3cache.AwsS3BuildCache) {
                    region = 'eu-west-1'
                    bucket = '$BUCKET_NAME'
                    prefix = 'build-cache/'
                    endpoint = 'localhost:${mockApp.port}'
                    push = true
                }
            }
        """.trimIndent()
        )
    }

    @ParameterizedTest
    @MethodSource("gradleVersionAndSettings")
    fun cacheStoreWorks(gradleVersion: String) {
        val outputFile = "build/out.txt"
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
    }
}
