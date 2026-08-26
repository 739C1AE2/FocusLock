package com.github739c1ae2.focuslock.ui.screen.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github739c1ae2.focuslock.database.LockRepository
import com.github739c1ae2.focuslock.database.ScheduleEntity
import com.github739c1ae2.focuslock.database.ScheduleWithProfileName
import com.github739c1ae2.focuslock.database.WEEKDAY_SET
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ScheduleListViewModel @Inject constructor(
    private val repository: LockRepository
) : ViewModel() {

    val createResult: SharedFlow<Long>
        field = MutableSharedFlow<Long>()

    val schedules: StateFlow<List<ScheduleWithProfileName>> = repository.observeAllSchedules()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun setScheduleActive(schedule: ScheduleEntity, active: Boolean) {
        viewModelScope.launch {
            repository.saveSchedule(schedule.copy(isActive = active))
        }
    }

    fun createSchedule(name: String) {
        val newSchedule = ScheduleEntity(
            name = name,
            daysOfWeek = WEEKDAY_SET,
            startMinute = 8 * 60,
            endMinute = 9 * 60,
            isActive = false
        )
        viewModelScope.launch {
            val newScheduleId = repository.saveSchedule(newSchedule)
            createResult.emit(newScheduleId)
        }
    }
}