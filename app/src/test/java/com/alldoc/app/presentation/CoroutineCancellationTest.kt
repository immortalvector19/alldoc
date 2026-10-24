package com.alldoc.app.presentation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CoroutineCancellationTest {

    sealed interface TestUiState {
        data object Loading : TestUiState
        data class Success(val data: String) : TestUiState
        data class Error(val message: String) : TestUiState
    }

    @Test
    fun testCancellationDoesNotEmitErrorState() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val stateFlow = MutableStateFlow<TestUiState>(TestUiState.Loading)

        // Simulate ViewModel load job with CancellationException rethrow
        val job = launch(testDispatcher) {
            try {
                // Simulate long I/O parsing
                delay(2000)
                stateFlow.value = TestUiState.Success("Loaded Document")
            } catch (t: Throwable) {
                // Critical guarantee: CancellationException is rethrown so it never displays an Error UI
                if (t is CancellationException) throw t
                stateFlow.value = TestUiState.Error(t.localizedMessage ?: "Failed")
            }
        }

        // Advance 500ms while still loading
        advanceTimeBy(500)
        assertEquals(TestUiState.Loading, stateFlow.value)

        // User navigates away or selects another file -> Cancel previous load job
        job.cancelAndJoin()
        advanceUntilIdle()

        // Verify that state did NOT turn into Error!
        assertNotEquals(
            "Cancelled load job must NEVER transition UI state into Error",
            TestUiState.Error("Cancelled"),
            stateFlow.value
        )
        assertEquals("State must remain Loading (or transition to new job)", TestUiState.Loading, stateFlow.value)
    }
}
