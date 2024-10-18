/*
 * Copyright 2020-2021 Vladimir Sitnikov <sitnikov.vladimir@gmail.com>
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
import org.gradle.api.internal.provider.DefaultProvider
import org.gradle.api.provider.Provider
import org.gradle.caching.BuildCacheService
import org.gradle.caching.BuildCacheServiceFactory
import org.slf4j.LoggerFactory
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3ClientBuilder
import software.amazon.awssdk.services.s3.S3Configuration
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
            describe("KMS Key ID", config.kmsKeyId)
            describe("Endpoint", config.endpoint)
            describe("Transfer Acceleration", config.transferAcceleration)
        }
        verifyConfig(config)
        return AwsS3BuildCacheService(
            { createS3Client(config) },
            config.bucket!!,
            config.prefix,
            config.kmsKeyId,
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

        config.apply {
            awsAccessKeyId =
                awsAccessKeyId ?: DefaultProvider { System.getenv("S3_BUILD_CACHE_ACCESS_KEY_ID") }
            awsSecretKey =
                awsSecretKey ?: DefaultProvider { System.getenv("S3_BUILD_CACHE_SECRET_KEY") }
            sessionToken =
                sessionToken ?: DefaultProvider { System.getenv("S3_BUILD_CACHE_SESSION_TOKEN") }
            awsProfile = awsProfile ?: DefaultProvider { System.getenv("S3_BUILD_CACHE_PROFILE") }
        }
    }

    private fun createS3Client(config: AwsS3BuildCache) = S3Client.builder().run {
        addHttpHeaders(config)
        addCredentials(config)
        region(Region.of(config.region))
        if (config.forcePathStyle) {
            forcePathStyle(true)
        }
        config.endpoint?.let {
            val endpoint = if (it.startsWith("http")) it else "https://${it}"
            endpointOverride(URI.create(endpoint))
        }
        transferAcceleration(config)
        build()
    }

    private fun S3ClientBuilder.addHttpHeaders(
        config: AwsS3BuildCache
    ) {
        config.headers?.let { headers ->
            overrideConfiguration {
                for ((key, value) in headers) {
                    if (key != null && value != null) {
                        it.putHeader(key, value)
                    }
                }
            }
        }
    }

    private fun S3ClientBuilder.addCredentials(config: AwsS3BuildCache) {
        val awsAccessKeyId = config.awsAccessKeyId?.getOrElse("")  ?: ""
        val awsSecretKey = config.awsSecretKey?.getOrElse("") ?: ""
        val credentials = when {
            config.credentialsProvider != null -> config.credentialsProvider
            awsAccessKeyId.isBlank() || awsSecretKey.isBlank() -> {
                val awsProfile = config.awsProfile?.getOrElse("")
                when {
                    config.lookupDefaultAwsCredentials -> return
                    !awsProfile.isNullOrBlank() ->
                        ProfileCredentialsProvider.create(awsProfile)

                    else -> AnonymousCredentialsProvider.create()
                }
            }

            else -> {
                val sessionToken = config.sessionToken?.getOrElse("") ?: ""
                StaticCredentialsProvider.create(
                    if (sessionToken.isNotEmpty() == true) {
                        AwsBasicCredentials.create(awsAccessKeyId, awsSecretKey)
                    } else {
                        AwsSessionCredentials.create(
                            awsAccessKeyId,
                            awsSecretKey,
                            sessionToken
                        )
                    }
                )
            }
        }
        credentialsProvider(credentials)
    }

    private fun S3ClientBuilder.transferAcceleration(config: AwsS3BuildCache) {
        val s3Conf = S3Configuration.builder().accelerateModeEnabled(config.transferAcceleration).build()
        serviceConfiguration(s3Conf)
    }
}
