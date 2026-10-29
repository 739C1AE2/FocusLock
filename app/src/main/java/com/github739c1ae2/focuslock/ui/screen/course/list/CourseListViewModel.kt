package com.github739c1ae2.focuslock.ui.screen.course.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github739c1ae2.focuslock.database.CourseListItem
import com.github739c1ae2.focuslock.database.CourseRepository
import com.github739c1ae2.focuslock.database.CourseTableEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject



@HiltViewModel
class CourseListViewModel @Inject constructor(
    private val repository: CourseRepository,
) : ViewModel() {

    val courseTable: Flow<CourseTableEntity> = repository.observeCourseTables()

    val courses: StateFlow<List<CourseListItem>> = repository.observeCourseEntityList()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )


    val createResult: SharedFlow<Long>
        field = MutableSharedFlow<Long>()


    fun createCourse(name: String) {
        viewModelScope.launch {
            val newCourseId = repository.createCourse(name.trim())
            createResult.emit(newCourseId)
        }
    }

    fun setCourseActive(courseId: Long, active: Boolean) {
        viewModelScope.launch {
            repository.updateCourseActiveStatus(courseId, active)
        }
    }

}
