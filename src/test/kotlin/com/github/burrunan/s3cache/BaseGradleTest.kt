/*
 * Copyright 2020 Vladimir Sitnikov <sitnikov.vladimir@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package com.github.burrunan.s3cache

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

abstract class BaseGradleTest {
    protected val gradleRunner = GradleRunner.create().withPluginClasspath()

    @TempDir
    protected lateinit var projectDir: Path

    fun Path.write(text: String) = this.toFile().writeText(text)
    fun Path.read(): String = this.toFile().readText()

    protected fun String.normalizeEol() = replace(Regex("[\r\n]+"), "\n")

    protected fun createSettings(extra: String = "") {
        val cp = gradleRunner.pluginClasspath.joinToString { "'${it.absolutePath}'" }

        projectDir.resolve("settings.gradle").write(
            """
                rootProject.name = 'sample'

                buildscript {
                  dependencies {
                    classpath(files($cp))
                  }
                }

                apply plugin: 'com.github.burrunan.s3-build-cache'

                $extra
            """
        )
    }

    protected fun prepare(gradleVersion: String, vararg arguments: String) =
        gradleRunner
            .withGradleVersion(gradleVersion)
            .withProjectDir(projectDir.toFile())
            .withArguments(*arguments)
            .forwardOutput()
}
