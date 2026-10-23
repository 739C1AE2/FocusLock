package com.github739c1ae2.focuslock.course

import android.content.ContentResolver
import android.net.Uri
import androidx.annotation.StringRes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException


sealed interface SourceType {
    data class File(
        val mimeTypes: List<String>
    ) : SourceType
}

sealed interface ParserSource {
    data class File(
        val uri: Uri,
        val contentResolver: ContentResolver,
    ) : ParserSource
}

sealed interface CourseTableParseResult {

    data class Success(
        val data: CourseTableImport,
        val issues: List<CourseImportIssue>
    ) : CourseTableParseResult

    data class Error(
        val issues: List<CourseImportIssue>
    ) : CourseTableParseResult
}

// FIXME: 这里使用了硬编码字符串，应当使用资源文件
const val DEFAULT_BASE_TIME_TABLE_NAME = "基础作息"

sealed class CourseImportParser<I : ParserSource> {
    @get:StringRes
    abstract val nameRes: Int

    @get:StringRes
    abstract val descRes: Int?

    abstract val sourceType: SourceType

    abstract val normalizeConfig: NormalizeConfig

    abstract fun onParse(source: I): CourseTableParseResult

    suspend fun parse(source: I): CourseTableParseResult = withContext(Dispatchers.IO) {
        val parseResult = onParse(source)
        if (parseResult !is CourseTableParseResult.Success) {
            return@withContext parseResult
        }
        val normalizeResult = CourseImportNormalizer.normalize(parseResult.data, normalizeConfig)
        CourseTableParseResult.Success(
            data = normalizeResult.data,
            issues = parseResult.issues + normalizeResult.issues
        )
    }
}

abstract class CourseImportFileParser(mimeTypes: List<String>) :
    CourseImportParser<ParserSource.File>() {

    final override val sourceType: SourceType = SourceType.File(mimeTypes)

    open fun onParseText(text: String): CourseTableParseResult {
        throw NotImplementedError("onParseText is not implemented")
    }

    override fun onParse(source: ParserSource.File): CourseTableParseResult {
        val text = try {
            source.contentResolver.openInputStream(source.uri)?.use { input ->
                input.bufferedReader(Charsets.UTF_8).readText()
            } ?: throw IOException("Failed to open input stream for URI: ${source.uri}")
        } catch (e: Exception) {
            return CourseTableParseResult.Error(
                issues = listOf(CourseImportIssue.ReadFileFailed(e))
            )
        }.removePrefix("\uFEFF").replace("\r\n", "\n")
        return onParseText(text)
    }
}

val COURSE_IMPORT_PARSERS: List<CourseImportParser<*>> = listOf(
    ShiguangCourseParser,
    SleepyNativeCourseParser
)

