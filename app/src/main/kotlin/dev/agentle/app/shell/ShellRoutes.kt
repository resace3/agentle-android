package dev.agentle.app.shell

import androidx.navigation3.runtime.NavKey

/** The main tab: talking to ChatGPT. */
data object ChatRoute : NavKey

/** A dashboard ChatGPT made, by [DashboardSpec.id]. */
data class UserDashboardRoute(val id: String) : NavKey

/** Every sensor the phone lists and whether the app can read it. */
data object SensorsRoute : NavKey
