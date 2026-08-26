package com.github739c1ae2.focuslock.ui.screen.adapter

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import com.github739c1ae2.focuslock.adapter.AdapterFactoryRegistry
import com.github739c1ae2.focuslock.adapter.ConfigSpec
import com.github739c1ae2.focuslock.adapter.ConfigValue
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable


@Serializable
data class AdapterConfigInfo(
    val adapterId: String,
    val config: Map<String, ConfigValue>
)

data class AdapterItem(
    val adapterId: String,
    @StringRes val adapterName: Int,
    @StringRes val description: Int?,
    val configSchema: List<ConfigSpec>,
    val config: Map<String, ConfigValue>
) {
    fun toConfigInfo(): AdapterConfigInfo {
        return AdapterConfigInfo(
            adapterId = adapterId,
            config = config
        )
    }
}

data class UiState(
    val selectedId: String? = null,
    val isDirty: Boolean = false,
    val adapters: List<AdapterItem> = emptyList()
)

@HiltViewModel(assistedFactory = AdapterConfigViewModel.Factory::class)
class AdapterConfigViewModel @AssistedInject constructor(
    @Assisted packageName: String,
    @Assisted original: AdapterConfigInfo?
) : ViewModel() {

    val state: StateFlow<UiState>
        field = MutableStateFlow<UiState>(UiState())

    init {
        val adapters = AdapterFactoryRegistry
            .getAvailableFactoriesForPackage(packageName)
            .map { factory ->
                if (factory.adapterId == original?.adapterId) {
                    AdapterItem(
                        adapterId = factory.adapterId,
                        adapterName = factory.adapterName,
                        description = factory.description,
                        configSchema = factory.configSchema,
                        config = original.config
                    )
                } else {
                    AdapterItem(
                        adapterId = factory.adapterId,
                        adapterName = factory.adapterName,
                        description = factory.description,
                        configSchema = factory.configSchema,
                        config = factory.defaultConfig(packageName)
                    )
                }
            }
        state.value = UiState(
            selectedId = original?.adapterId,
            adapters = adapters
        )
    }

    fun toggleAdapterSelection(adapterId: String?) {
        state.update {
            it.copy(selectedId = adapterId, isDirty = true)
        }
    }

    fun updateConfig(newConfig: AdapterConfigInfo) {
        state.update {
            val updatedAdapters = it.adapters.map { configInfo ->
                if (configInfo.adapterId == newConfig.adapterId) {
                    configInfo.copy(config = newConfig.config)
                } else {
                    configInfo
                }
            }
            it.copy(
                adapters = updatedAdapters,
                isDirty = true
            )
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(packageName: String, original: AdapterConfigInfo?): AdapterConfigViewModel
    }
}


