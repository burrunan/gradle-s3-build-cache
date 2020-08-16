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

import com.github.burrunan.s3cache.AwsS3BuildCache
import org.gradle.caching.BuildCacheService
import org.gradle.caching.BuildCacheServiceFactory
import org.slf4j.LoggerFactory
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.S3AsyncClientBuilder
import java.net.URI

private val logger = LoggerFactory.getLogger(AwsS3BuildCacheServiceFactory::class.java)

class AwsS3BuildCacheServiceFactory : BuildCacheServiceFactory<AwsS3BuildCache> {
    override fun createBuildCacheService(
        config: AwsS3BuildCache,
        describer: BuildCacheServiceFactory.Describer
    ): BuildCacheService {
        logger.debug("Start creating S3 build cache service")
        describer.apply {
            type("AWS S3")
            describe("Region", config.region)
            describe("Bucket", config.bucket)
            describe("Reduced Redundancy", config.isReducedRedundancy)
            describe("Prefix", config.prefix)
            describe("Endpoint", config.endpoint)
        }
        verifyConfig(config)
        return AwsS3BuildCacheService(
            createS3Client(config),
            config.bucket!!,
            config.prefix,
            config.isReducedRedundancy,
            config.maximumCachedObjectLength,
            config.showStatistics,
            config.showStatisticsWhenImpactExceeds,
            config.showStatisticsWhenSavingsExceeds,
            config.showStatisticsWhenWasteExceeds,
            config.showStatisticsWhenTransferExceeds
        )
    }

    private fun BuildCacheServiceFactory.Describer.describe(name: String, value: Any?) {
        if (value != null) {
            config(name, value.toString())
        }
    }

    private fun verifyConfig(config: AwsS3BuildCache) {
        check(!config.region.isNullOrEmpty()) { "S3 build cache has no AWS region configured" }
        check(!config.bucket.isNullOrEmpty()) { "S3 build cache has no bucket configured" }
    }

    private fun createS3Client(config: AwsS3BuildCache) = S3AsyncClient.builder().run {
        addHttpHeaders(config)
        addCredentials(config)
        region(Region.of(config.region))
        config.endpoint?.let {
            val endpoint = if (it.startsWith("http")) it else "https://${it}"
            endpointOverride(URI.create(endpoint))
        }
        build()
    }

    private fun S3AsyncClientBuilder.addHttpHeaders(
        config: AwsS3BuildCache
    ) {
        config.headers?.let { headers ->
            overrideConfiguration {
                for ((key, value) in config.headers ?: mapOf()) {
                    if (key != null && value != null) {
                        it.putHeader(key, value)
                    }
                }
            }
        }
    }

    private fun S3AsyncClientBuilder.addCredentials(config: AwsS3BuildCache) {
        val credentials = when {
            config.awsAccessKeyId.isNullOrBlank() || config.awsSecretKey.isNullOrBlank() -> when {
                config.lookupDefaultAwsCredentials -> return
                else -> AnonymousCredentialsProvider.create()
            }
            !config.sessionToken.isNullOrEmpty() ->
                StaticCredentialsProvider.create(AwsSessionCredentials.create(
                        config.awsAccessKeyId,
                        config.awsSecretKey,
                        config.sessionToken
                ))
            else -> StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(config.awsAccessKeyId, config.awsSecretKey))
        }
        credentialsProvider(credentials)
    }
}
