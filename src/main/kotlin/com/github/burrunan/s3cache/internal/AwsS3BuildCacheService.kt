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

import com.github.burrunan.s3cache.internal.awssdk.InputStreamResponse
import com.github.burrunan.s3cache.internal.awssdk.OutputStreamRequest
import org.gradle.api.logging.LogLevel
import org.gradle.api.logging.Logging
import org.gradle.caching.*
import org.gradle.util.GradleVersion
import software.amazon.awssdk.core.ResponseBytes
import software.amazon.awssdk.core.async.AsyncRequestBody
import software.amazon.awssdk.core.async.AsyncResponseTransformer
import software.amazon.awssdk.core.exception.SdkServiceException
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.model.*
import java.io.ByteArrayOutputStream
import java.util.*
import java.util.concurrent.CompletionException
import kotlin.math.absoluteValue

private val logger = Logging.getLogger(AwsS3BuildCacheService::class.java)

class AwsS3BuildCacheService internal constructor(
    private val s3: S3AsyncClient,
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

        s3.close()

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
                        (if (cacheLoads.bytes != 0L) ", processed: ${cacheLoads.bytes.byteUnits()}" else "")
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
        logger.info("Loading cache entry '{}' from S3 bucket", bucketPath)
        val s3Object: InputStreamResponse<GetObjectResponse>
        try {
            s3Object = s3.getObject(GetObjectRequest.builder().bucket(bucketName).key(bucketPath).build(), InputStreamResponse())
                    .join()
        } catch (e : CompletionException) {
            val cause = e.cause
            when (cause) {
                is NoSuchBucketException -> {
                    throw BuildCacheException("Bucket '${bucketName}' not found", e)
                }
                is NoSuchKeyException -> {
                    logger.info(
                            "Did not find cache item '{}' '{}' in S3 bucket '{}'",
                            key,
                            bucketPath,
                            bucketName
                    )
                }
                is SdkServiceException -> {
                    if (cause.statusCode() == 403) {
                        logger.info(
                                "Got 403 (Forbidden) when fetching cache item '{}' '{}' in S3 bucket",
                                key,
                                bucketPath
                        )
                    } else {
                        logger.info(
                                "Unexpected error when fetching cache item '{}' '{}' in S3 bucket",
                                key,
                                bucketPath,
                                cause
                        )
                    }
                }
                else -> {
                    logger.info(
                            "Unexpected error when fetching cache item '{}' '{}' in S3 bucket",
                            key,
                            bucketPath,
                            cause
                    )
                }
            }
            return false
        }
        val contentLength = s3Object.response().contentLength()
        if (contentLength > maximumCachedObjectLength) {
            logger.info(
                    "Cache item '{}' '{}' in S3 bucket size is {}, and it exceeds maximumCachedObjectLength {}. Will skip the retrieval",
                    key,
                    bucketPath,
                    contentLength,
                    maximumCachedObjectLength
            )
            s3Object.close()
            return false
        }
        // Propagate metadata so task finished listener can compute time saved/wasted
        CURRENT_TASK.get()?.let {
            it.metadata = CacheEntryMetadata(s3Object.response().metadata())
        }
        bytesProcessed(contentLength)
        try {
            cacheHits {
                reader.readFrom(s3Object)
            }
        } catch (e: Throwable) {
            logger.info(
                    "Unexpected error when fetching cache item '{}' '{}' in S3 bucket",
                    key,
                    bucketPath,
                    e
            )
            return false
        }
        return true
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
        val metadata = writer.readBuildMetadata() ?: CURRENT_TASK.get()?.let {
            CacheEntryMetadata(
                buildInvocationId = buildId,
                identity = it.path,
                executionTime = System.currentTimeMillis() - it.executionStarted,
                operatingSystem = System.getProperty("os.name"),
                gradleVersion = GradleVersion.current().toString()
            )
        }
        val userMetadata = hashMapOf<String, String>()
        metadata?.appendTo(userMetadata)
        try {
            val request = PutObjectRequest.builder().run {
                bucket(bucketName)
                key(bucketPath)
                contentLength(writer.size)
                contentType(BUILD_CACHE_CONTENT_TYPE)
                metadata(userMetadata)
                if (reducedRedundancy) {
                    storageClass(StorageClass.REDUCED_REDUNDANCY)
                }
                build()
            }
            if (writer.file() != null) {
                s3.putObject(request, AsyncRequestBody.fromFile(writer.file())).join()
            } else {
                val body = OutputStreamRequest(writer.size)
                val future = s3.putObject(request, body)
                writer.writeTo(body)
                future.join()
            }
        } catch (e: Throwable) {
            throw BuildCacheException("Error while storing cache object in S3 bucket", e)
        }
    }
}
