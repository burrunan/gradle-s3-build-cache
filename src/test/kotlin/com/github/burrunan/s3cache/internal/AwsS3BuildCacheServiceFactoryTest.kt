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
import org.gradle.caching.BuildCacheServiceFactory
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider

class AwsS3BuildCacheServiceFactoryTest {
    private lateinit var subject: AwsS3BuildCacheServiceFactory
    private lateinit var buildCacheDescriber: BuildCacheServiceFactory.Describer

    private inner class NoopBuildCacheDescriber : BuildCacheServiceFactory.Describer {
        override fun type(type: String) = this
        override fun config(name: String, value: String) = this
    }

    @BeforeEach
    fun setUp() {
        subject = AwsS3BuildCacheServiceFactory()
        buildCacheDescriber = NoopBuildCacheDescriber()
    }

    private fun buildCache(action: AwsS3BuildCache.() -> Unit) = AwsS3BuildCache().apply(action)

    @Test
    fun testWhat() {
        val conf = buildCache {
            region = "us-west-1"
            bucket = "my-bucket"
        }
        val service = subject.createBuildCacheService(conf, buildCacheDescriber)
        Assertions.assertNotNull(service)
    }

    @Test
    fun prefix() {
        val conf = buildCache {
            region = "us-west-1"
            bucket = "my-bucket"
            prefix = "cache"
        }
        val service = subject.createBuildCacheService(conf, buildCacheDescriber)
        Assertions.assertNotNull(service)
    }

    @Test
    fun testNullHeaders() {
        val conf = buildCache {
            region = "us-west-1"
            bucket = "my-bucket"
            headers = null
        }
        val service = subject.createBuildCacheService(conf, buildCacheDescriber)
        Assertions.assertNotNull(service)
    }

    @Test
    fun testNullHeaderName() {
        val conf = buildCache {
            region = "us-west-1"
            bucket = "my-bucket"
            headers = mapOf(null to "foo")
        }
        val service = subject.createBuildCacheService(conf, buildCacheDescriber)
        Assertions.assertNotNull(service)
    }

    @Test
    fun testNullHeaderValue() {
        val conf = buildCache {
            region = "us-west-1"
            bucket = "my-bucket"
            headers = mapOf("x-foo" to null)
        }
        val service = subject.createBuildCacheService(conf, buildCacheDescriber)
        Assertions.assertNotNull(service)
    }

    @Test
    fun testIllegalConfigWithoutRegion() {
        val conf = buildCache {
            bucket = "my-bucket"
        }
        assertThrows<IllegalStateException> {
            subject.createBuildCacheService(conf, buildCacheDescriber)
        }
    }

    @Test
    fun testIllegalConfigWithoutBucket() {
        val conf = buildCache {
            region = "us-west-1"
        }
        assertThrows<IllegalStateException> {
            subject.createBuildCacheService(conf, buildCacheDescriber)
        }
    }

    @Test
    fun testAddAWSSessionCredentials() {
        val conf = buildCache {
            bucket = "my-bucket"
            region = "us-west-1"
            awsAccessKeyId = "any aws access key"
            awsSecretKey = "any secret key"
            sessionToken = "any session token"
        }
        val service = subject.createBuildCacheService(conf, buildCacheDescriber)
        Assertions.assertNotNull(service)
    }

    @Test
    fun testAWSProfileCredentials() {
        val conf = buildCache {
            bucket = "my-bucket"
            region = "us-west-1"
            awsProfile = "any aws profile"
        }
        val service = subject.createBuildCacheService(conf, buildCacheDescriber)
        Assertions.assertNotNull(service)
    }

    @Test
    fun testAWSProviderCredentials() {
        val conf = buildCache {
            bucket = "my-bucket"
            region = "us-west-1"
            credentialsProvider = AnonymousCredentialsProvider.create()
        }
        val service = subject.createBuildCacheService(conf, buildCacheDescriber)
        Assertions.assertNotNull(service)
    }

    @Test
    fun kmsKeyId() {
        val conf = buildCache {
            region = "us-west-1"
            bucket = "my-bucket"
            kmsKeyId = "972393be-674f-4bdc-87ff-ea1b2588a1c6"
        }
        val service = subject.createBuildCacheService(conf, buildCacheDescriber)
        Assertions.assertNotNull(service)
    }
}
