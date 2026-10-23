package com.github739c1ae2.focuslock.ui.screen.course

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github739c1ae2.focuslock.database.CourseImportBinding
import com.github739c1ae2.focuslock.course.CourseImportFileParser
import com.github739c1ae2.focuslock.course.CourseImportParser
import com.github739c1ae2.focuslock.database.CourseImportUseCase
import com.github739c1ae2.focuslock.course.CourseTableParseResult
import com.github739c1ae2.focuslock.database.GLOBAL_PROFILE_ID
import com.github739c1ae2.focuslock.database.LockRepository
import com.github739c1ae2.focuslock.course.ParserSource
import com.github739c1ae2.focuslock.database.ProfileEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ImportUiState {
    object Idle : ImportUiState
    data class Parsing(
        val parser: CourseImportParser<*>
    ) : ImportUiState

    data class Review(
        val data: CourseTableParseResult,
        val binding: CourseImportBinding = CourseImportBinding.PROFILE,
        val baseProfileId: Long = GLOBAL_PROFILE_ID
    ) : ImportUiState

    object Writing : ImportUiState
    object Success: ImportUiState
    data class ImportError(val message: String) : ImportUiState
}

@HiltViewModel
class CourseImportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val useCase: CourseImportUseCase,
    lockRepository: LockRepository
) : ViewModel() {


    val profiles: StateFlow<List<ProfileEntity>> = lockRepository.observeAllProfileEntities()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val uiState: StateFlow<ImportUiState>
        field = MutableStateFlow<ImportUiState>(ImportUiState.Idle)

    fun setImportBinding(binding: CourseImportBinding) {
        val state = uiState.value as? ImportUiState.Review ?: return
        uiState.value = ImportUiState.Review(
            data = state.data,
            binding = binding,
            baseProfileId = state.baseProfileId
        )
    }

    fun setBaseProfileId(profileId: Long) {
        val state = uiState.value as? ImportUiState.Review ?: return
        uiState.value = ImportUiState.Review(
            data = state.data,
            binding = state.binding,
            baseProfileId = profileId
        )
    }

    fun parseFile(parser: CourseImportFileParser, uri: Uri) {
        val source = ParserSource.File(uri, context.contentResolver)
        viewModelScope.launch {
            uiState.value = ImportUiState.Parsing(parser = parser)
            val result = parser.parse(source)
            uiState.value = ImportUiState.Review(data = result)
        }
    }

    fun cancelImport() {
        uiState.value = ImportUiState.Idle
    }

    fun import() {
        val state = uiState.value as? ImportUiState.Review ?: return
        val parseResult = state.data as? CourseTableParseResult.Success ?: return
        viewModelScope.launch {
            try {
                uiState.value = ImportUiState.Writing
                useCase.importCourseTable(
                    tableImport = parseResult.data,
                    binding = state.binding,
                    baseProfileId = state.baseProfileId
                )
                uiState.value = ImportUiState.Success
            } catch (e: Exception) {
                uiState.value = ImportUiState.ImportError(message = e.message ?: "Unknown error")
            }
        }
    }

}
