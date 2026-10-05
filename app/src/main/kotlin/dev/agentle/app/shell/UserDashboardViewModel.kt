package dev.agentle.app.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * A dashboard screen: the user's saved dashboards, each one's numbers from [DashboardCalculator] and Google's A2UI
 * renderer state for drawing them ([screens]).
 */
@HiltViewModel
class UserDashboardViewModel @Inject constructor(private val calculator: DashboardCalculator, val store: DashboardStore) : ViewModel() {
    val screens: A2uiScreenHost = A2uiScreenHost(viewModelScope)

    fun data(spec: DashboardSpec): Flow<DashboardData> = calculator.data(spec)
}
