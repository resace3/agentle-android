package dev.agentle.feature.insights.testing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** Sets `Dispatchers.Main` to a [StandardTestDispatcher]; `runTest` then shares its scheduler with `viewModelScope`. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class MainDispatcherRule(val dispatcher: TestDispatcher = StandardTestDispatcher()) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

/** Collects [flow] for the rest of the test (keeps `WhileSubscribed` state flows running). */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun <T> TestScope.keepCollecting(flow: Flow<T>) {
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect { } }
}

/** Records every item of [flow] (a ViewModel's effects) into the returned list. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun <T> TestScope.record(flow: Flow<T>): List<T> {
    val items = mutableListOf<T>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect { items += it } }
    return items
}
