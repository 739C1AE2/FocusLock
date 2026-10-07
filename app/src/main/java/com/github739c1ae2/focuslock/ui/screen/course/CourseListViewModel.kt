package com.github739c1ae2.focuslock.ui.screen.course

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github739c1ae2.focuslock.database.CourseImportBinding
import com.github739c1ae2.focuslock.database.CourseImportRequest
import com.github739c1ae2.focuslock.database.CourseListItem
import com.github739c1ae2.focuslock.database.CourseRepository
import com.github739c1ae2.focuslock.database.CourseTableEntity
import com.github739c1ae2.focuslock.database.LockRepository
import com.github739c1ae2.focuslock.database.ProfileEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed interface CourseImportEvent {
    data class Success(
        val tableName: String,
        val courseCount: Int,
        val sessionCount: Int
    ) : CourseImportEvent

    data object EmptyFile : CourseImportEvent

    data class Failure(val message: String?) : CourseImportEvent
}

@HiltViewModel
class CourseListViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: CourseRepository,
    lockRepository: LockRepository
) : ViewModel() {

    val courseTable: Flow<CourseTableEntity> = repository.observeCourseTable()

    val courses: StateFlow<List<CourseListItem>> = repository.observeCourseList()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val profiles: StateFlow<List<ProfileEntity>> = lockRepository.observeAllProfiles()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val createResult: SharedFlow<Long>
        field = MutableSharedFlow<Long>()

    val importResult: SharedFlow<CourseImportEvent>
        field = MutableSharedFlow<CourseImportEvent>()

    fun import(
        uri: Uri,
        binding: CourseImportBinding,
        baseProfileId: Long,
        tableName: String
    ) {
        viewModelScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        input.bufferedReader(Charsets.UTF_8).readText()
                    }
                }
                if (text.isNullOrBlank()) {
                    importResult.emit(CourseImportEvent.EmptyFile)
                    return@launch
                }
                val result = repository.importShiguangCourses(
                    CourseImportRequest(
                        json = text,
                        binding = binding,
                        baseProfileId = baseProfileId,
                        tableName = tableName
                    )
                )
                importResult.emit(
                    CourseImportEvent.Success(
                        tableName = result.tableName,
                        courseCount = result.courseCount,
                        sessionCount = result.sessionCount
                    )
                )
            } catch (e: Exception) {
                importResult.emit(CourseImportEvent.Failure(e.message))
            }
        }
    }

    fun createCourse(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val newCourseId = repository.createCourse(name.trim())
            createResult.emit(newCourseId)
        }
    }

    fun setCourseActive(courseId: Long, active: Boolean) {
        viewModelScope.launch {
            repository.updateCourseActive(courseId, active)
        }
    }

}
