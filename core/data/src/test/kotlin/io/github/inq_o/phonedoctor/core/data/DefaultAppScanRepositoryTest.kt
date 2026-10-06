/*
 * Copyright (C) 2022 The Android Open Source Project
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

package io.github.inq_o.phonedoctor.core.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import io.github.inq_o.phonedoctor.core.data.DefaultAppScanRepository
import io.github.inq_o.phonedoctor.core.database.AppScan
import io.github.inq_o.phonedoctor.core.database.AppScanDao

/**
 * Unit tests for [DefaultAppScanRepository].
 */
@OptIn(ExperimentalCoroutinesApi::class) // TODO: Remove when stable
class DefaultAppScanRepositoryTest {

    @Test
    fun appScans_newItemSaved_itemIsReturned() = runTest {
        val repository = DefaultAppScanRepository(FakeAppScanDao())

        repository.add("Repository")

        assertEquals(repository.appScans.first().size, 1)
    }

}

private class FakeAppScanDao : AppScanDao {

    private val data = mutableListOf<AppScan>()

    override fun getAppScans(): Flow<List<AppScan>> = flow {
        emit(data)
    }

    override suspend fun insertAppScan(item: AppScan) {
        data.add(0, item)
    }
}
