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

import com.github.burrunan.s3cache.internal.readBuildMetadata
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MetadataReaderTest {
    @Test
    internal fun readMetadata() {
        val tgz =
            MetadataReaderTest::class.java.getResource("/8c6178372e88d2e7acca28f26b79ff37.tgz")
        assertEquals(
            tgz.asFile.readBuildMetadata()?.appendTo(mutableMapOf()),
            mapOf(
                "buildInvocationId" to "m6e4sb4dmrc5zdnykb5v465pry",
                "identity" to ":validatePlugins",
                "executionTime" to "17",
                "operatingSystem" to "Mac OS X",
                "gradleVersion" to "6.3"
            )
        )
    }
}
