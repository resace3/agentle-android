package dev.agentle.core.ui.navigation

/**
 * Navigation requests from feature screens. `:app` implements it over its back stack; tests use a recording fake.
 * Screens never hold the back stack themselves.
 */
public interface AppNavigator {
    /** Opens [route] on top of the current screen. */
    public fun navigate(route: AppRoute)

    /** Leaves the current screen; at the root this does nothing (the system handles leaving the app). */
    public fun back()

    /** Clears the back stack and shows [route]: the end of onboarding, or the restart after "delete everything". */
    public fun resetTo(route: AppRoute)
}
