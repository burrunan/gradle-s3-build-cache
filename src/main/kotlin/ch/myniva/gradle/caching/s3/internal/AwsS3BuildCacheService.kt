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
package ch.myniva.gradle.caching.s3.internal

import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model.AmazonS3Exception
import com.amazonaws.services.s3.model.ObjectMetadata
import com.amazonaws.services.s3.model.PutObjectRequest
import com.amazonaws.services.s3.model.StorageClass
import org.gradle.api.logging.LogLevel
import org.gradle.api.logging.Logging
import org.gradle.caching.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

private val logger = Logging.getLogger(AwsS3BuildCacheService::class.java)

class AwsS3BuildCacheService internal constructor(
    private val s3: AmazonS3,
    private val bucketName: String,
    private val path: String?,
    private val reducedRedundancy: Boolean,
    private val maximumCachedObjectLength: Long
) : BuildCacheService {
    companion object {
        private const val BUILD_CACHE_CONTENT_TYPE = "application/vnd.gradle.build-cache-artifact"
    }

    private val cacheLoads = Stopwatch()
    private val cacheHits = Stopwatch()
    private val cacheStores = Stopwatch()

    override fun close() {
        fun Long.mib() = (this + 512L * 1024) / (1024L * 1024)

        s3.shutdown()
        if (cacheLoads.starts != 0) {
            logger.log(
                if (cacheHits.elapsed > 1000) LogLevel.LIFECYCLE else LogLevel.INFO,
                "S3 cache reads: ${cacheHits.starts}, requests: ${cacheLoads.starts}, " +
                        "elapsed time: ${cacheLoads.elapsed}ms, processed: ${cacheLoads.bytes.mib()}MiB"
            )
        }
        if (cacheStores.starts != 0) {
            logger.lifecycle(
                "S3 cache writes: ${cacheStores.starts}, " +
                        "elapsed time: ${cacheStores.elapsed}ms, sent to cache: ${cacheStores.bytes.mib()}MiB"
            )
        }
    }

    private fun BuildCacheKey.getBucketPath() = if (path.isNullOrEmpty()) {
        hashCode
    } else {
        "$path/$hashCode"
    }

    override fun load(key: BuildCacheKey, reader: BuildCacheEntryReader): Boolean = cacheLoads {
        loadInternal(key, reader)
    }

    private fun Stopwatch.loadInternal(key: BuildCacheKey, reader: BuildCacheEntryReader): Boolean {
        val bucketPath = key.getBucketPath()
        try {
            s3.getObject(bucketName, bucketPath).use { s3Object ->
                val contentLength = s3Object.objectMetadata.contentLength
                if (contentLength > maximumCachedObjectLength) {
                    logger.info(
                        "Cache item '{}' '{}' in S3 bucket size is {}, and it exceeds maximumCachedObjectLength {}. Will skip the retrieval",
                        key.displayName,
                        bucketPath,
                        contentLength,
                        maximumCachedObjectLength
                    )
                    return false
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
                    key.displayName,
                    bucketPath
                )
                return false
            }
            logger.info(
                "Unexpected error when fetching cache item '{}' '{}' in S3 bucket",
                key.displayName,
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
                key.displayName,
                bucketPath,
                itemSize,
                maximumCachedObjectLength
            )
            return
        }
        bytesProcessed(itemSize)
        logger.info("Start storing cache entry '{}' in S3 bucket", bucketPath)
        val meta = ObjectMetadata().apply {
            contentType = BUILD_CACHE_CONTENT_TYPE
            contentLength = writer.size
        }
        try {
            val stream = ByteArrayInputStream(
                ByteArrayOutputStream()
                    .also { os -> writer.writeTo(os) }
                    .toByteArray()
            )
            val request = PutObjectRequest(bucketName, bucketPath, stream, meta)
            if (reducedRedundancy) {
                request.withStorageClass(StorageClass.ReducedRedundancy)
            }
            s3.putObject(request)
        } catch (e: IOException) {
            throw BuildCacheException("Error while storing cache object in S3 bucket", e)
        }
    }
}
