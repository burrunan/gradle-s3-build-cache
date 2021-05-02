/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.burrunan.s3cache.internal

import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model.AmazonS3Exception
import com.amazonaws.services.s3.model.ObjectMetadata
import com.amazonaws.services.s3.model.PutObjectRequest
import com.amazonaws.services.s3.model.StorageClass
import org.gradle.api.logging.LogLevel
import org.gradle.api.logging.Logging
import org.gradle.caching.*
import org.gradle.util.GradleVersion
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.*
import kotlin.math.absoluteValue

private val logger = Logging.getLogger(AwsS3BuildCacheService::class.java)

class AwsS3BuildCacheService internal constructor(
    s3Factory: () -> AmazonS3,
    private val bucketName: String,
    private val prefix: String?,
    private val reducedRedundancy: Boolean,
    private val maximumCachedObjectLength: Long,
    private val showStatistics: Boolean,
    private val showStatisticsWhenImpactExceeds: Long,
    private val showStatisticsWhenSavingsExceeds: Long,
    private val showStatisticsWhenWasteExceeds: Long,
    private val showStatisticsWhenTransferExceeds: Long
) : BuildCacheService {
    companion object {
        private const val BUILD_CACHE_CONTENT_TYPE = "application/vnd.gradle.build-cache-artifact"
    }

    // S3 client is created lazily, so it is created at execution time rather than configuration time
    private val s3 by lazy(s3Factory)

    private val buildId = "%x".format(Random().nextLong())

    private val cacheLoads = Stopwatch()
    private val cacheLoadSavings = Stopwatch()
    private val cacheLoadWaste = Stopwatch()
    private val cacheHits = Stopwatch()
    private val cacheStores = Stopwatch()

    override fun close() {
        fun Long.byteUnits() = when {
            this < 5 * 1024 -> "${this} B"
            this < 5 * 1024 * 1204 -> "${(this + 512L) / (1024L)} KiB"
            this < 5L * 1024 * 1204 * 1024 -> "${(this + 512L * 1024) / (1024L * 1024)} MiB"
            else -> "${(this + 512L * 1024 * 1024) / (1024L * 1024 * 1024)} GiB"
        }

        fun Long.timeUnits() = when {
            this == 0L -> "0s"
            this < 2000 -> "${this}ms"
            this < 120 * 1000 -> "${(this + 500) / 1000}s"
            else -> {
                val seconds = (this + 500) / 1000
                val minutes = seconds / 60
                val hours = minutes / 60
                if (hours > 0) {
                    "%02d:%02d:%02d".format(hours % 60, minutes % 60, seconds % 60)
                } else {
                    "%02d:%02d".format(minutes % 60, seconds % 60)
                }
            }
        }

        fun Long.savedWasted(noImpact: String = "no impact") = when {
            this > 0 -> "${timeUnits()} saved"
            this < 0 -> "${(-this).timeUnits()} wasted"
            else -> noImpact
        }

        s3.shutdown()

        if (!showStatistics) {
            return
        }

        if (cacheLoads.starts != 0) {
            val impact = cacheLoadSavings.elapsed - cacheLoadWaste.elapsed
            val summary = when {
                cacheLoadSavings.elapsed == 0L && cacheLoadWaste.elapsed == 0L ->
                    "no impact"
                cacheLoadWaste.elapsed == 0L ->
                    "${cacheLoadSavings.elapsed.savedWasted()} on hits"
                cacheLoadSavings.elapsed == 0L ->
                    "${(-cacheLoadWaste.elapsed).savedWasted()} on misses"
                else ->
                    "${impact.savedWasted()} (${cacheLoadSavings.elapsed.savedWasted()} on hits, " +
                            "${(-cacheLoadWaste.elapsed).savedWasted()} on misses)"
            }
            logger.log(
                if (impact.absoluteValue > showStatisticsWhenImpactExceeds ||
                    cacheLoadSavings.elapsed.absoluteValue > showStatisticsWhenSavingsExceeds ||
                    cacheLoadWaste.elapsed.absoluteValue > showStatisticsWhenWasteExceeds ||
                    cacheLoads.bytes > showStatisticsWhenTransferExceeds
                ) LogLevel.LIFECYCLE else LogLevel.INFO,
                "S3 cache $summary" +
                        (if (cacheLoads.starts != cacheHits.starts) ", reads: ${cacheLoads.starts}" else "") +
                        (if (cacheHits.starts != 0) ", hits: ${cacheHits.starts}" else "") +
                        (if (cacheLoads.elapsed != 0L) ", elapsed: ${cacheLoads.elapsed.timeUnits()}" else "") +
                        (if (cacheLoads.bytes != 0L) ", received: ${cacheLoads.bytes.byteUnits()}" else "")
            )
        }
        if (cacheStores.starts != 0) {
            logger.lifecycle(
                "S3 cache writes: ${cacheStores.starts}, " +
                        "elapsed: ${cacheStores.elapsed.timeUnits()}, sent to cache: ${cacheStores.bytes.byteUnits()}"
            )
        }
    }

    private fun BuildCacheKey.getBucketPath() = if (prefix.isNullOrEmpty()) {
        hashCode
    } else {
        "$prefix$hashCode"
    }

    override fun load(key: BuildCacheKey, reader: BuildCacheEntryReader): Boolean {
        val loadStarted = cacheLoads.elapsed

        return cacheLoads {
            loadInternal(key, reader)
        }.apply {
            CURRENT_TASK.get()?.let {
                it.cacheLoadDuration = cacheLoads.elapsed - loadStarted
                it.cacheLoadSavingsStopwatch = cacheLoadSavings
                it.cacheLoadWasteStopwatch = cacheLoadWaste
            }
        }
    }

    private fun Stopwatch.loadInternal(key: BuildCacheKey, reader: BuildCacheEntryReader): Boolean {
        val bucketPath = key.getBucketPath()
        try {
            logger.info("Loading cache entry '{}' from S3 bucket", bucketPath)
            s3.getObject(bucketName, bucketPath).use { s3Object ->
                val contentLength = s3Object.objectMetadata.contentLength
                if (contentLength > maximumCachedObjectLength) {
                    logger.info(
                        "Cache item '{}' '{}' in S3 bucket size is {}, and it exceeds maximumCachedObjectLength {}. Will skip the retrieval",
                        key,
                        bucketPath,
                        contentLength,
                        maximumCachedObjectLength
                    )
                    return false
                }
                // Propagate metadata so task finished listener can compute time saved/wasted
                CURRENT_TASK.get()?.let {
                    it.metadata = CacheEntryMetadata(s3Object.objectMetadata.userMetadata)
                }
                bytesProcessed(contentLength)
                cacheHits {
                    reader.readFrom(s3Object.objectContent)
                }
            }
            return true
        } catch (e: AmazonS3Exception) {
            if (e.statusCode == 403 || e.statusCode == 404) {
                logger.info(
                    when (e.statusCode) {
                        403 -> "Got 403 (Forbidden) when fetching cache item '{}' '{}' in S3 bucket"
                        else -> "Did not find cache item '{}' '{}' in S3 bucket"
                    },
                    key,
                    bucketPath
                )
                return false
            }
            logger.info(
                "Unexpected error when fetching cache item '{}' '{}' in S3 bucket",
                key,
                bucketPath,
                e
            )
            return false;
        }
    }

    override fun store(key: BuildCacheKey, writer: BuildCacheEntryWriter) = cacheStores {
        storeInternal(key, writer)
    }

    private fun Stopwatch.storeInternal(key: BuildCacheKey, writer: BuildCacheEntryWriter) {
        val bucketPath = key.getBucketPath()
        val itemSize = writer.size
        if (itemSize > maximumCachedObjectLength) {
            logger.info(
                "Cache item '{}' '{}' in S3 bucket size is {}, and it exceeds maximumCachedObjectLength {}. Will skip caching it.",
                key,
                bucketPath,
                itemSize,
                maximumCachedObjectLength
            )
            return
        }
        bytesProcessed(itemSize)
        logger.info("Storing cache entry '{}' to S3 bucket", bucketPath)
        val meta = ObjectMetadata().apply {
            contentType = BUILD_CACHE_CONTENT_TYPE
            contentLength = writer.size
        }
        val metadata = writer.readBuildMetadata() ?: CURRENT_TASK.get()?.let {
            CacheEntryMetadata(
                buildInvocationId = buildId,
                identity = it.path,
                executionTime = System.currentTimeMillis() - it.executionStarted,
                operatingSystem = System.getProperty("os.name"),
                gradleVersion = GradleVersion.current().toString()
            )
        }
        metadata?.appendTo(meta.userMetadata)
        try {
            val request = writer.file()?.let {
                // If file is avaliable, use it directly
                PutObjectRequest(bucketName, bucketPath, it)
            } ?: PutObjectRequest(
                bucketName, bucketPath,
                ByteArrayInputStream(
                    ByteArrayOutputStream()
                        .also { os -> writer.writeTo(os) }
                        .toByteArray()
                ), meta
            )
            request.metadata = meta
            if (reducedRedundancy) {
                request.withStorageClass(StorageClass.ReducedRedundancy)
            }
            s3.putObject(request)
        } catch (e: IOException) {
            throw BuildCacheException("Error while storing cache object in S3 bucket", e)
        }
    }
}
