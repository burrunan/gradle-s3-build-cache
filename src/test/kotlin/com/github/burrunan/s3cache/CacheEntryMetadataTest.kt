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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CacheEntryMetadataTest {
    @Test
    internal fun lowerCaseKeysFromS3() {
        // S3 returns user metadata keys lower-cased
        val metadata = CacheEntryMetadata(
            mapOf(
                "executiontime" to "43814",
                "buildinvocationid" to "qsqsxzthmbaybk3qb3qngc62vy",
                "gradleversion" to "9.1.0",
                "operatingsystem" to "Mac OS X",
                "identity" to ":sr-commons:spotbugsMain"
            )
        )
        assertEquals(43814L, metadata.executionTime)
        assertEquals("qsqsxzthmbaybk3qb3qngc62vy", metadata.buildInvocationId)
        assertEquals("9.1.0", metadata.gradleVersion)
        assertEquals("Mac OS X", metadata.operatingSystem)
        assertEquals(":sr-commons:spotbugsMain", metadata.identity)
    }

    @Test
    internal fun camelCaseKeys() {
        val metadata = CacheEntryMetadata(
            mapOf(
                "executionTime" to "17",
                "buildInvocationId" to "m6e4sb4dmrc5zdnykb5v465pry",
                "gradleVersion" to "6.3",
                "operatingSystem" to "Mac OS X",
                "identity" to ":validatePlugins"
            )
        )
        assertEquals(17L, metadata.executionTime)
        assertEquals("m6e4sb4dmrc5zdnykb5v465pry", metadata.buildInvocationId)
        assertEquals("6.3", metadata.gradleVersion)
        assertEquals("Mac OS X", metadata.operatingSystem)
        assertEquals(":validatePlugins", metadata.identity)
    }

    @Test
    internal fun roundTrip() {
        val original = CacheEntryMetadata(
            buildInvocationId = "abc",
            identity = ":compileJava",
            executionTime = 123L,
            operatingSystem = "Linux",
            gradleVersion = "8.0"
        )
        val lowerCased = original.toMap().mapKeys { it.key.lowercase() }
        assertEquals(original.toMap(), CacheEntryMetadata(lowerCased).toMap())
    }
}
