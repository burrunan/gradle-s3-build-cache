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

import ch.myniva.gradle.caching.s3.AwsS3BuildCache
import com.amazonaws.ClientConfiguration
import com.amazonaws.auth.AWSStaticCredentialsProvider
import com.amazonaws.auth.AnonymousAWSCredentials
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.auth.BasicSessionCredentials
import com.amazonaws.client.builder.AwsClientBuilder
import com.amazonaws.services.s3.AmazonS3ClientBuilder
import org.gradle.caching.BuildCacheService
import org.gradle.caching.BuildCacheServiceFactory
import org.slf4j.LoggerFactory

class AwsS3BuildCacheServiceFactory : BuildCacheServiceFactory<AwsS3BuildCache> {
    companion object {
        private val logger = LoggerFactory.getLogger(AwsS3BuildCacheServiceFactory::class.java)
    }

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
            describe("Path", config.path)
            describe("Endpoint", config.endpoint)
        }
        verifyConfig(config)
        return AwsS3BuildCacheService(
            createS3Client(config),
            config.bucket!!,
            config.path,
            config.isReducedRedundancy,
            config.maximumCachedObjectLength
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

    private fun createS3Client(config: AwsS3BuildCache) = AmazonS3ClientBuilder.standard().run {
        addHttpHeaders(config)
        addCredentials(config)
        if (config.endpoint.isNullOrEmpty()) {
            withRegion(config.region)
        } else {
            withEndpointConfiguration(
                AwsClientBuilder.EndpointConfiguration(
                    config.endpoint,
                    config.region
                )
            )
        }
        build()
    }

    private fun AmazonS3ClientBuilder.addHttpHeaders(
        config: AwsS3BuildCache
    ) {
        clientConfiguration = ClientConfiguration().apply {
            for ((key, value) in config.headers ?: mapOf()) {
                if (key != null && value != null) {
                    addHeader(key, value)
                }
            }
        }
    }

    private fun AmazonS3ClientBuilder.addCredentials(config: AwsS3BuildCache) {
        val credentials = when {
            config.awsAccessKeyId.isNullOrBlank() && config.awsSecretKey.isNullOrBlank() -> when {
                config.lookupDefaultAwsCredentials -> return
                else -> AnonymousAWSCredentials()
            }
            config.awsAccessKeyId.isNullOrEmpty() ->
                BasicAWSCredentials(config.awsAccessKeyId, config.awsSecretKey)
            else ->
                BasicSessionCredentials(
                    config.awsAccessKeyId,
                    config.awsSecretKey,
                    config.sessionToken
                )
        }

        withCredentials(AWSStaticCredentialsProvider(credentials))
    }
}
