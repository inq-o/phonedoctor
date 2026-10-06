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

package io.github.inq_o.phonedoctor.feature.appscan.impl

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import io.github.inq_o.phonedoctor.core.data.AppScanRepository
import io.github.inq_o.phonedoctor.feature.appscan.impl.AppScanUiState.Error
import io.github.inq_o.phonedoctor.feature.appscan.impl.AppScanUiState.Loading
import io.github.inq_o.phonedoctor.feature.appscan.impl.AppScanUiState.Success
import javax.inject.Inject

@HiltViewModel
class AppScanViewModel @Inject constructor(
    private val appScanRepository: AppScanRepository
) : ViewModel() {

    val uiState: StateFlow<AppScanUiState> = appScanRepository
        .appScans.map<List<String>, AppScanUiState> { Success(data = it) }
        .catch { emit(Error(it)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Loading)

    fun addAppScan(name: String) {
        viewModelScope.launch {
            appScanRepository.add(name)
        }
    }
}

sealed interface AppScanUiState {
    object Loading : AppScanUiState
    data class Error(val throwable: Throwable) : AppScanUiState
    data class Success(val data: List<String>) : AppScanUiState
}
