package com.alldoc.app.presentation.home

import com.alldoc.app.core.model.Document
import com.alldoc.app.core.model.DocumentCategory

data class HomeUiState(
    val isLoading: Boolean = false,
    val selectedCategory: DocumentCategory = DocumentCategory.ALL,
    val searchQuery: String = "",
    val selectedTab: Int = 0, // 0 = Recent, 1 = Favorites
    val documents: List<Document> = emptyList(),
    val error: String? = null
)
