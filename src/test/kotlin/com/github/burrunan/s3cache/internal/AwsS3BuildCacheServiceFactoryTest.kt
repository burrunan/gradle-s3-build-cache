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
import org.gradle.kotlin.dsl.newInstance
import org.gradle.testfixtures.ProjectBuilder
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

    private fun buildCache(action: AwsS3BuildCache.() -> Unit): AwsS3BuildCache {
        val p = ProjectBuilder.builder().withName("hello").build()
        return p.objects.newInstance<AwsS3BuildCache>().apply(action)
    }

    @Test
    fun testWhat() {
        val conf = buildCache {
            region.set("us-west-1")
            bucket.set("my-bucket")
        }
        val service = subject.createBuildCacheService(conf, buildCacheDescriber)
        Assertions.assertNotNull(service)
    }

    @Test
    fun prefix() {
        val conf = buildCache {
            region.set("us-west-1")
            bucket.set("my-bucket")
            prefix.set("cache")
        }
        val service = subject.createBuildCacheService(conf, buildCacheDescriber)
        Assertions.assertNotNull(service)
    }

    @Test
    fun testNullHeaders() {
        val conf = buildCache {
            region.set("us-west-1")
            bucket.set("my-bucket")
        }
        val service = subject.createBuildCacheService(conf, buildCacheDescriber)
        Assertions.assertNotNull(service)
    }

    @Test
    fun testNullHeaderName() {
        val conf = buildCache {
            region.set("us-west-1")
            bucket.set("my-bucket")
            headers.put("header-name", "foo")
        }
        val service = subject.createBuildCacheService(conf, buildCacheDescriber)
        Assertions.assertNotNull(service)
    }

    @Test
    fun testNullHeaderValue() {
        val conf = buildCache {
            region.set("us-west-1")
            bucket.set("my-bucket")
            headers.put("x-foo", "value")
        }
        val service = subject.createBuildCacheService(conf, buildCacheDescriber)
        Assertions.assertNotNull(service)
    }

    @Test
    fun testIllegalConfigWithoutRegion() {
        val conf = buildCache {
            bucket.set("my-bucket")
        }
        assertThrows<IllegalStateException> {
            subject.createBuildCacheService(conf, buildCacheDescriber)
        }
    }

    @Test
    fun testIllegalConfigWithoutBucket() {
        val conf = buildCache {
            region.set("us-west-1")
        }
        assertThrows<IllegalStateException> {
            subject.createBuildCacheService(conf, buildCacheDescriber)
        }
    }

    @Test
    fun testAddAWSSessionCredentials() {
        val conf = buildCache {
            bucket.set("my-bucket")
            region.set("us-west-1")
            awsAccessKeyId.set("any aws access key")
            awsSecretKey.set("any secret key")
            sessionToken.set("any session token")
        }
        val service = subject.createBuildCacheService(conf, buildCacheDescriber)
        Assertions.assertNotNull(service)
    }

    @Test
    fun testAWSProfileCredentials() {
        val conf = buildCache {
            bucket.set("my-bucket")
            region.set("us-west-1")
            awsProfile.set("any aws profile")
        }
        val service = subject.createBuildCacheService(conf, buildCacheDescriber)
        Assertions.assertNotNull(service)
    }

    @Test
    fun testAWSProviderCredentials() {
        val conf = buildCache {
            bucket.set("my-bucket")
            region.set("us-west-1")
            credentialsProvider = AnonymousCredentialsProvider.create()
        }
        val service = subject.createBuildCacheService(conf, buildCacheDescriber)
        Assertions.assertNotNull(service)
    }

    @Test
    fun kmsKeyId() {
        val conf = buildCache {
            region.set("us-west-1")
            bucket.set("my-bucket")
            kmsKeyId.set("972393be-674f-4bdc-87ff-ea1b2588a1c6")
        }
        val service = subject.createBuildCacheService(conf, buildCacheDescriber)
        Assertions.assertNotNull(service)
    }
}
