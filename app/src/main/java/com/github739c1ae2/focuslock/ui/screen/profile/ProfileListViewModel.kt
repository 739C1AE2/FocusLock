package com.github739c1ae2.focuslock.ui.screen.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github739c1ae2.focuslock.database.LockRepository
import com.github739c1ae2.focuslock.database.ProfileEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject


@HiltViewModel
class ProfileListViewModel @Inject constructor(
    private val repository: LockRepository
) : ViewModel() {

    val createResult: SharedFlow<Long>
        field = MutableSharedFlow<Long>()

    val profiles: StateFlow<List<ProfileEntity>> = repository.observeAllProfiles()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )


    fun createProfile(name: String) {
        viewModelScope.launch {
            val newProfileId = repository.createProfile(name)
            createResult.emit(newProfileId)
        }
    }
}