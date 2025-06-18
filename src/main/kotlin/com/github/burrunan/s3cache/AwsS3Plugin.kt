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

import com.github.burrunan.s3cache.internal.AwsS3BuildCacheServiceFactory
import com.github.burrunan.s3cache.internal.CURRENT_TASK
import com.github.burrunan.s3cache.internal.TaskPerformanceInfo
import org.gradle.api.Plugin
import org.gradle.api.Task
import org.gradle.api.configuration.BuildFeatures
import org.gradle.api.execution.TaskExecutionListener
import org.gradle.api.initialization.Settings
import org.gradle.api.logging.Logging
import org.gradle.api.tasks.TaskState
import org.gradle.kotlin.dsl.support.serviceOf
import org.gradle.util.GradleVersion

private val logger = Logging.getLogger(AwsS3Plugin::class.java)

class AwsS3Plugin : Plugin<Settings> {
    override fun apply(settings: Settings) {
        logger.info("Registering S3 build cache")
        settings.buildCache.registerBuildCacheService(
            AwsS3BuildCache::class.java,
            AwsS3BuildCacheServiceFactory::class.java
        )
        registerTaskExecutionListener(settings)
    }

    private val Settings.configurationCacheEnabled: Boolean
        get() {
            if (GradleVersion.current() >= GradleVersion.version("8.5")) {
                return serviceOf<BuildFeatures>().configurationCache.active.get()
            }
            return try {
                startParameter.javaClass.getMethod("isConfigurationCache").invoke(startParameter) as Boolean
            } catch (e: Exception) {
                false
            }
        }

    private fun registerTaskExecutionListener(settings: Settings) {
        if (settings.configurationCacheEnabled) {
            return
        }
        settings.gradle.taskGraph.addTaskExecutionListener(object : TaskExecutionListener {
            override fun beforeExecute(task: Task) {
                CURRENT_TASK.set(TaskPerformanceInfo(task.path, System.currentTimeMillis()))
            }

            override fun afterExecute(task: Task, state: TaskState) {
                CURRENT_TASK.get()?.run {
                    val executionFinished = System.currentTimeMillis()
                    val taskDuration = executionFinished - executionStarted
                    when (state.skipMessage) {
                        "FROM-CACHE" ->
                            cacheLoadSavingsStopwatch?.increment(
                                (metadata?.executionTime ?: 0) - taskDuration
                            )
                        null ->
                            cacheLoadWasteStopwatch?.increment(
                                cacheLoadDuration ?: 0
                            )
                    }
                    Unit
                }
                // Avoid memory leaks
                CURRENT_TASK.remove()
            }
        })
    }
}
