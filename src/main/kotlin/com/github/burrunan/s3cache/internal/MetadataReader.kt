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
package com.github.burrunan.s3cache.internal

import com.github.burrunan.s3cache.internal.tar.TarInputStream
import org.gradle.caching.BuildCacheEntryWriter
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.zip.GZIPInputStream

class CacheEntryMetadata(
    val buildInvocationId: String? = null,
    val identity: String? = null,
    val executionTime: Long? = null,
    val operatingSystem: String? = null,
    val gradleVersion: String? = null
) {
    fun toMap(): Map<String, String> = mutableMapOf<String, String>().apply {
        buildInvocationId?.let { put("buildInvocationId", it) }
        identity?.let { put("identity", it) }
        executionTime?.let { put("executionTime", it.toString()) }
        operatingSystem?.let { put("operatingSystem", it) }
        gradleVersion?.let { put("gradleVersion", it) }
    }
}

fun CacheEntryMetadata(map: Map<String, String>): CacheEntryMetadata {
    // A camelCase key that toMap writes comes back from S3 lower-cased, so the lookup ignores case
    // https://docs.aws.amazon.com/AmazonS3/latest/userguide/UsingMetadata.html
    val lookup = TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER).apply { putAll(map) }
    return CacheEntryMetadata(
        buildInvocationId = lookup["buildInvocationId"],
        identity = lookup["identity"],
        // A foreign value that does not parse must not throw: Gradle disables the remote cache on error
        executionTime = lookup["executionTime"]?.toLongOrNull(),
        operatingSystem = lookup["operatingSystem"],
        gradleVersion = lookup["gradleVersion"]
    )
}

fun BuildCacheEntryWriter.readBuildMetadata(): CacheEntryMetadata? = try {
    file()?.readBuildMetadata()
} catch (ignore: Throwable) {
    null
}

fun File.readBuildMetadata(): CacheEntryMetadata? {
    try {
        inputStream().use { fileStream ->
            GZIPInputStream(fileStream).use { ungzip ->
                TarInputStream(ungzip, StandardCharsets.UTF_8.name()).use { tar ->
                    val entry = tar.nextEntry ?: return null
                    if (!entry.isFile || entry.name != "METADATA" || entry.size > 10000) {
                        return null
                    }
                    val buffer = ByteArray(entry.size.toInt())
                    if (tar.read(buffer) != buffer.size) {
                        return null
                    }
                    val parsed = Properties().apply { load(buffer.inputStream()) }
                    return CacheEntryMetadata(
                        buildInvocationId = parsed.getProperty("buildInvocationId"),
                        identity = parsed.getProperty("identity"),
                        executionTime = parsed.getProperty("executionTime")?.toLong(),
                        operatingSystem = parsed.getProperty("operatingSystem"),
                        gradleVersion = parsed.getProperty("gradleVersion")
                    )
                }
            }
        }
    } catch (ignore: Throwable) {
    }
    return null
}
