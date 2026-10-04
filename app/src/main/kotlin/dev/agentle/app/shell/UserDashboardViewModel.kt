package dev.agentle.app.shell

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/** A dashboard screen: the user's saved dashboards and each one's numbers from [DashboardCalculator]. */
@HiltViewModel
class UserDashboardViewModel @Inject constructor(private val calculator: DashboardCalculator, val store: DashboardStore) : ViewModel() {
    fun data(spec: DashboardSpec): Flow<DashboardData> = calculator.data(spec)
}
