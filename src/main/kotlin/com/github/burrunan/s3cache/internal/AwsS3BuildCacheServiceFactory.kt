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
import org.gradle.caching.BuildCacheService
import org.gradle.caching.BuildCacheServiceFactory
import org.slf4j.LoggerFactory
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.auth.credentials.WebIdentityTokenFileCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3ClientBuilder
import software.amazon.awssdk.services.s3.S3Configuration
import java.net.URI
import java.nio.file.Paths

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
            describe("Reduced Redundancy", config.reducedRedundancy)
            describe("Prefix", config.prefix)
            describe("KMS Key ID", config.kmsKeyId)
            describe("Endpoint", config.endpoint)
            describe("Transfer Acceleration", config.transferAcceleration)
        }
        verifyConfig(config)
        return AwsS3BuildCacheService(
            { createS3Client(config) },
            config.bucket.get(),
            config.prefix.orNull,
            config.kmsKeyId.orNull,
            config.reducedRedundancy.get(),
            config.maximumCachedObjectLength.get(),
            config.showStatistics.get(),
            config.showStatisticsWhenImpactExceeds.get(),
            config.showStatisticsWhenSavingsExceeds.get(),
            config.showStatisticsWhenWasteExceeds.get(),
            config.showStatisticsWhenTransferExceeds.get()
        )
    }

    private fun BuildCacheServiceFactory.Describer.describe(name: String, value: Any?) {
        if (value != null) {
            config(name, value.toString())
        }
    }

    private fun verifyConfig(config: AwsS3BuildCache) {
        check(config.region.isPresent) { "S3 build cache has no AWS region configured" }
        check(config.bucket.isPresent) { "S3 build cache has no bucket configured" }
    }

    private fun createS3Client(config: AwsS3BuildCache) = S3Client.builder().run {
        addHttpHeaders(config)
        addCredentials(config)
        region(Region.of(config.region.get()))
        if (config.forcePathStyle.get()) {
            forcePathStyle(true)
        }
        config.endpoint.orNull?.let {
            val endpoint = if (it.startsWith("http")) it else "https://${it}"
            endpointOverride(URI.create(endpoint))
        }
        transferAcceleration(config)
        config.s3ClientActions.forEach { it.execute(this) }
        build()
    }

    private fun S3ClientBuilder.addHttpHeaders(
        config: AwsS3BuildCache
    ) {
        config.headers.orNull?.let { headers ->
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
        val credentials = when {
            config.credentialsProvider != null ->
                config.credentialsProvider

            config.awsWebIdentityTokenFile.isPresent ->
                WebIdentityTokenFileCredentialsProvider.builder()
                    .roleArn(config.awsRoleARN.get())
                    .webIdentityTokenFile(Paths.get(config.awsWebIdentityTokenFile.get()))
                    .build()

            config.awsAccessKeyId.orNull.isNullOrBlank() || config.awsSecretKey.orNull.isNullOrBlank() ->
                when {
                    !config.awsProfile.orNull.isNullOrBlank() ->
                        ProfileCredentialsProvider.create(config.awsProfile.get())
                    config.lookupDefaultAwsCredentials.get() -> return
                    else -> AnonymousCredentialsProvider.create()
                }

            else ->
                StaticCredentialsProvider.create(
                    if (config.sessionToken.orNull.isNullOrEmpty()) {
                        AwsBasicCredentials.create(config.awsAccessKeyId.get(), config.awsSecretKey.get())
                    } else {
                        AwsSessionCredentials.create(
                            config.awsAccessKeyId.get(),
                            config.awsSecretKey.get(),
                            config.sessionToken.get()
                        )
                    }
                )
        }
        credentialsProvider(credentials)
    }

    private fun S3ClientBuilder.transferAcceleration(config: AwsS3BuildCache) {
        val s3Conf = S3Configuration.builder().apply {
            accelerateModeEnabled(config.transferAcceleration.get())
            config.s3ConfigurationActions.forEach { it.execute(this) }
        }.build()
        serviceConfiguration(s3Conf)
    }
}
