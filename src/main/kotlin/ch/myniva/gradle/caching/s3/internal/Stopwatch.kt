/*
 * Copyright 2019 Vladimir Sitnikov <sitnikov.vladimir@gmail.com>
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
package ch.myniva.gradle.caching.s3.internal

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class Stopwatch {
    val elapsed: Long get() = elapsedTime.get()
    val starts: Int get() = startCount.get()
    val bytes: Long get() = bytesProcessed.get()

    private var elapsedTime = AtomicLong()
    private var startCount = AtomicInteger()
    private var bytesProcessed = AtomicLong()

    fun bytesProcessed(bytes: Long) {
        bytesProcessed.addAndGet(bytes)
    }

    fun increment(duration: Long, bytesProcessed: Long = 0) {
        this.elapsedTime.addAndGet(duration)
        this.bytesProcessed.addAndGet(bytesProcessed)
        this.startCount.incrementAndGet()
    }

    operator fun <T> invoke(bytes: Long = 0, action: Stopwatch.() -> T): T {
        val startTime = System.currentTimeMillis()
        startCount.incrementAndGet()
        if (bytes != 0L) {
            bytesProcessed.addAndGet(bytes)
        }
        try {
            return action()
        } finally {
            elapsedTime.addAndGet(System.currentTimeMillis() - startTime)
        }
    }
}
