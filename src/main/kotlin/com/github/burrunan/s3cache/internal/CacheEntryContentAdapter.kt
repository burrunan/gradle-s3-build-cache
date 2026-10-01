package com.github.burrunan.s3cache.internal

import org.gradle.caching.BuildCacheEntryWriter
import org.gradle.util.GradleVersion
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.ContentStreamProvider
import java.io.ByteArrayOutputStream

internal const val BUILD_CACHE_CONTENT_TYPE = "application/vnd.gradle.build-cache-artifact"

/**
 * Abstracts how the cache entry contents are obtained from a [BuildCacheEntryWriter].
 */
internal interface CacheEntryContentAdapter {
    fun createRequestBody(writer: BuildCacheEntryWriter): RequestBody
    fun readBuildMetadata(writer: BuildCacheEntryWriter): CacheEntryMetadata?
}

/**
 * The adapter matching the running Gradle version, selected once,
 * so cache operations perform no version checks.
 */
internal val cacheEntryContentAdapter: CacheEntryContentAdapter =
    if (GradleVersion.current().baseVersion >= GradleVersion.version("9.7")) {
        StreamingCacheEntryContentAdapter
    } else {
        LegacyCacheEntryContentAdapter
    }

/**
 * Streams cache entry contents via `BuildCacheEntryWriter.getInputStream()`, which returns
 * a fresh stream on every call, so the AWS SDK can re-read the contents on upload retries.
 * Must only be used on Gradle 9.7+, older versions fail with [NoSuchMethodError].
 */
internal object StreamingCacheEntryContentAdapter : CacheEntryContentAdapter {
    override fun createRequestBody(writer: BuildCacheEntryWriter): RequestBody =
        RequestBody.fromContentProvider(
            // the provider closes each superseded stream, the SDK closes the last one
            ContentStreamProvider.fromInputStreamSupplier { writer.inputStream },
            writer.size,
            BUILD_CACHE_CONTENT_TYPE
        )

    override fun readBuildMetadata(writer: BuildCacheEntryWriter): CacheEntryMetadata? = try {
        writer.inputStream.use { it.readBuildMetadata() }
    } catch (ignore: Throwable) {
        null
    }
}

/**
 * Before Gradle 9.7 the only supported way to get the contents was `writeTo(OutputStream)`,
 * so this adapter reflectively reads the writer's backing file when possible,
 * and buffers the contents in memory otherwise.
 */
internal object LegacyCacheEntryContentAdapter : CacheEntryContentAdapter {
    override fun createRequestBody(writer: BuildCacheEntryWriter): RequestBody =
        writer.file()?.let { RequestBody.fromFile(it) }
            ?: RequestBody.fromBytes(
                ByteArrayOutputStream()
                    .also { os -> writer.writeTo(os) }
                    .toByteArray()
            )

    override fun readBuildMetadata(writer: BuildCacheEntryWriter): CacheEntryMetadata? = try {
        writer.file()?.readBuildMetadata()
    } catch (ignore: Throwable) {
        null
    }
}
