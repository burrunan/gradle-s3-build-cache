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
package com.github.burrunan.s3cache

import org.gradle.api.Action
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.ProviderFactory
import org.gradle.caching.configuration.AbstractBuildCache
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.services.s3.S3ClientBuilder
import software.amazon.awssdk.services.s3.S3Configuration
import javax.inject.Inject

abstract class AwsS3BuildCache @Inject constructor(providers: ProviderFactory) : AbstractBuildCache() {
    abstract val region: Property<String>
    abstract val bucket: Property<String>
    abstract val prefix: Property<String>
    abstract val kmsKeyId: Property<String> // nullable?
    abstract val maximumCachedObjectLength: Property<Long>
    abstract val reducedRedundancy: Property<Boolean>
    abstract val endpoint: Property<String> // nullable?
    abstract val forcePathStyle: Property<Boolean>
    abstract val headers: MapProperty<String, String>// Map<String?, String?>? = null
    abstract val awsAccessKeyId: Property<String> // nullable?
    abstract val awsSecretKey: Property<String> // nullable?
    abstract val sessionToken: Property<String> // nullable?
    abstract val awsProfile: Property<String> // nullable?

    // OIDC Configuration
    abstract val awsWebIdentityTokenFile: Property<String> // nullable?
    abstract val awsRoleARN: Property<String> // nullable?

    abstract val lookupDefaultAwsCredentials: Property<Boolean>
    var credentialsProvider: AwsCredentialsProvider? = null // TODO: convert to property?
    abstract val showStatistics: Property<Boolean>
    abstract val showStatisticsWhenImpactExceeds: Property<Long>
    abstract val showStatisticsWhenSavingsExceeds: Property<Long>
    abstract val showStatisticsWhenWasteExceeds: Property<Long>
    abstract val showStatisticsWhenTransferExceeds: Property<Long>
    abstract val transferAcceleration: Property<Boolean> // nullable? = false
    internal val s3ClientActions = mutableListOf<Action<S3ClientBuilder>>()
    internal val s3ConfigurationActions = mutableListOf<Action<S3Configuration.Builder>>()

    fun s3client(action: Action<S3ClientBuilder>) {
        s3ClientActions += action
    }

    fun s3configuration(action: Action<S3Configuration.Builder>) {
        s3ConfigurationActions += action
    }

    init {
        prefix.convention("cache/")
        forcePathStyle.convention(false)
        maximumCachedObjectLength.convention(50 * 1024 * 1024)
        reducedRedundancy.convention(true)
        awsAccessKeyId.convention(providers.environmentVariable("S3_BUILD_CACHE_ACCESS_KEY_ID"))
        awsSecretKey.convention(providers.environmentVariable("S3_BUILD_CACHE_SECRET_KEY"))
        sessionToken.convention(providers.environmentVariable("S3_BUILD_CACHE_SESSION_TOKEN"))
        awsProfile.convention(providers.environmentVariable("S3_BUILD_CACHE_PROFILE"))
        awsWebIdentityTokenFile.convention(providers.environmentVariable("AWS_WEB_IDENTITY_TOKEN_FILE"))
        awsRoleARN.convention(providers.environmentVariable("AWS_ROLE_ARN"))
        lookupDefaultAwsCredentials.convention(false)
        showStatistics.convention(true)
        showStatisticsWhenImpactExceeds.convention(100)
        showStatisticsWhenSavingsExceeds.convention(100)
        showStatisticsWhenWasteExceeds.convention(100)
        showStatisticsWhenTransferExceeds.convention(10 * 1024 * 1024)
        transferAcceleration.convention(false)
    }
}
