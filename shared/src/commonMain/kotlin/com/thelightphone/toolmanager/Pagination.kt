package com.thelightphone.toolmanager

import kotlinx.serialization.Serializable

@Serializable
data class PaginatedResponse<T>(
    val data: List<T>,
    val pagination: PaginationInfo
)

@Serializable
data class PaginationInfo(
    val currentPage: Int,
    val totalPages: Int,
    val pageSize: Int,
    val totalItems: Int,
    val hasNext: Boolean,
    val hasPrevious: Boolean
)

data class PageRequest(
    val page: Int = 1,
    val size: Int = 20,
    val sortBy: SortBy = SortBy.DATE,
    val sortOrder: SortOrder = SortOrder.DESC,
    // When true, recursively descends into every subdirectory and returns only files
    val flatten: Boolean = false
) {
    val offset: Int get() = (page - 1) * size
}

enum class SortOrder {
    ASC, DESC
}

enum class SortBy {
    DATE, SIZE, NAME, KIND
}

// Extension to validate and create PageRequest from query parameters
fun createPageRequest(
    page: String?,
    size: String?,
    sortBy: String?,
    sortOrder: String?,
    flatten: String? = null
): Result<PageRequest> = try {
    val pageInt = page?.toIntOrNull() ?: 1
    val sizeInt = size?.toIntOrNull() ?: 20
    val order = sortOrder?.uppercase()?.let { so ->
        SortOrder.entries.find { it.name == so.uppercase() }
    } ?: SortOrder.DESC
    val sort = sortBy?.uppercase()?.let { sb ->
        SortBy.entries.find { it.name == sb.uppercase() }
    } ?: SortBy.DATE
    Result.success(PageRequest(pageInt, sizeInt, sort, order, flatten == "true"))
} catch (e: IllegalArgumentException) {
    Result.failure(e)
}