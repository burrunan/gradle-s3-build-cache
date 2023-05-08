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

import org.gradle.caching.configuration.AbstractBuildCache

open class AwsS3BuildCache : AbstractBuildCache() {
    var region: String? = null
    var bucket: String? = null
    var prefix: String? = "cache/"
    var maximumCachedObjectLength: Long = 50 * 1024 * 1024
    var isReducedRedundancy = true
    var endpoint: String? = null
    var headers: Map<String?, String?>? = null
    var awsAccessKeyId: String? = System.getenv("S3_BUILD_CACHE_ACCESS_KEY_ID")
    var awsSecretKey: String? = System.getenv("S3_BUILD_CACHE_SECRET_KEY")
    var sessionToken: String? = System.getenv("S3_BUILD_CACHE_SESSION_TOKEN")
    var awsProfile: String? = System.getenv("S3_BUILD_CACHE_PROFILE")
    var lookupDefaultAwsCredentials: Boolean = false
    var showStatistics: Boolean = true
    var showStatisticsWhenImpactExceeds: Long = 100
    var showStatisticsWhenSavingsExceeds: Long = 100
    var showStatisticsWhenWasteExceeds: Long = 100
    var showStatisticsWhenTransferExceeds: Long = 10 * 1024 * 1024
}
