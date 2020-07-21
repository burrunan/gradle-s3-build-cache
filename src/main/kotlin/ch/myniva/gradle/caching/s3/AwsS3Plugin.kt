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
package ch.myniva.gradle.caching.s3

import ch.myniva.gradle.caching.s3.internal.AwsS3BuildCacheServiceFactory
import org.gradle.api.Plugin
import org.gradle.api.initialization.Settings
import org.gradle.api.logging.Logging

private val logger = Logging.getLogger(AwsS3Plugin::class.java)

class AwsS3Plugin : Plugin<Settings> {
    override fun apply(settings: Settings) {
        logger.info("Registering S3 build cache")
        settings.buildCache.registerBuildCacheService(
            AwsS3BuildCache::class.java,
            AwsS3BuildCacheServiceFactory::class.java
        )
    }
}
