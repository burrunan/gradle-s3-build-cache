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

import com.github.burrunan.s3cache.internal.CacheEntryMetadata
import com.github.burrunan.s3cache.internal.readBuildMetadata
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments.argumentSet
import org.junit.jupiter.params.provider.MethodSource

/**
 * S3 returns user metadata keys lower-cased, so the reader used to find none of them: a cache hit
 * reported no execution time, and the statistics line claimed the cache wasted time on every hit.
 */
class MetadataReaderTest {
    companion object {
        private const val BUILD_INVOCATION_ID = "m6e4sb4dmrc5zdnykb5v465pry"

        @JvmStatic
        fun keySpellings() = listOf(
            argumentSet(
                "lower-cased, as S3 returns them",
                mapOf(
                    "buildinvocationid" to BUILD_INVOCATION_ID,
                    "identity" to ":validatePlugins",
                    "executiontime" to "17",
                    "operatingsystem" to "Mac OS X",
                    "gradleversion" to "6.3"
                )
            ),
            argumentSet(
                "camelCase, as toMap writes them",
                mapOf(
                    "buildInvocationId" to BUILD_INVOCATION_ID,
                    "identity" to ":validatePlugins",
                    "executionTime" to "17",
                    "operatingSystem" to "Mac OS X",
                    "gradleVersion" to "6.3"
                )
            )
        )
    }

    @Test
    fun readMetadata() {
        val tgz =
            MetadataReaderTest::class.java.getResource("/8c6178372e88d2e7acca28f26b79ff37.tgz")
        assertEquals(
            mapOf(
                "buildInvocationId" to BUILD_INVOCATION_ID,
                "identity" to ":validatePlugins",
                "executionTime" to "17",
                "operatingSystem" to "Mac OS X",
                "gradleVersion" to "6.3"
            ),
            tgz.asFile.readBuildMetadata()?.toMap()
        )
    }

    @ParameterizedTest
    @MethodSource("keySpellings")
    fun everyKeyIsParsedRegardlessOfCase(metadata: Map<String, String>) {
        val parsed = CacheEntryMetadata(metadata)
        assertAll(
            { assertEquals(BUILD_INVOCATION_ID, parsed.buildInvocationId, "buildInvocationId") },
            { assertEquals(":validatePlugins", parsed.identity, "identity") },
            { assertEquals(17L, parsed.executionTime, "executionTime") },
            { assertEquals("Mac OS X", parsed.operatingSystem, "operatingSystem") },
            { assertEquals("6.3", parsed.gradleVersion, "gradleVersion") }
        )
    }

    @Test
    fun metadataWithoutKeys() {
        assertEquals(mapOf<String, String>(), CacheEntryMetadata(emptyMap()).toMap())
    }

    @Test
    fun executionTimeThatIsNotANumber() {
        val parsed = CacheEntryMetadata(
            mapOf("executiontime" to "not a number", "identity" to ":validatePlugins")
        )
        assertAll(
            { assertEquals(null, parsed.executionTime, "executionTime") },
            { assertEquals(":validatePlugins", parsed.identity, "identity") }
        )
    }
}
