package com.alldoc.app.presentation.home

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.alldoc.app.core.data.repository.DocumentRepository
import com.alldoc.app.core.model.DocumentCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class HomeViewModel @JvmOverloads constructor(
    application: Application,
    private val repository: DocumentRepository = DocumentRepository(application)
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(HomeUiState(isLoading = true))
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        loadDocuments()
    }

    fun selectCategory(category: DocumentCategory) {
        _uiState.update { it.copy(selectedCategory = category) }
        loadDocuments()
    }

    fun selectTab(tabIndex: Int) {
        _uiState.update { it.copy(selectedTab = tabIndex) }
        loadDocuments()
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        loadDocuments()
    }

    fun toggleFavorite(uriString: String, isFavorite: Boolean) {
        viewModelScope.launch {
            repository.toggleFavorite(uriString, isFavorite)
        }
    }

    fun deleteDocument(uriString: String) {
        viewModelScope.launch {
            repository.deleteDocument(uriString)
        }
    }

    fun renameDocument(uriString: String, newName: String) {
        viewModelScope.launch {
            repository.renameDocument(uriString, newName)
        }
    }

    fun registerDocumentAccess(uri: Uri) {
        viewModelScope.launch {
            repository.recordDocumentAccess(uri)
        }
    }

    private fun loadDocuments() {
        val currentState = _uiState.value
        viewModelScope.launch {
            val flow = when {
                currentState.searchQuery.isNotBlank() -> repository.search(currentState.searchQuery)
                currentState.selectedTab == 1 -> repository.getFavorites()
                else -> repository.getByCategory(currentState.selectedCategory)
            }

            flow.catch { e ->
                _uiState.update { it.copy(isLoading = false, error = e.localizedMessage) }
            }.collectLatest { docs ->
                _uiState.update { it.copy(isLoading = false, documents = docs, error = null) }
            }
        }
    }
}
