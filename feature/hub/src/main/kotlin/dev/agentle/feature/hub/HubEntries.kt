package dev.agentle.feature.hub

import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import dev.agentle.core.ui.navigation.AppNavigator
import dev.agentle.core.ui.navigation.AppRoute
import dev.agentle.feature.hub.dashboard.DashboardRoute
import dev.agentle.feature.hub.permissions.PermissionCenterRoute
import dev.agentle.feature.hub.permissions.PermissionCenterViewModel
import dev.agentle.feature.hub.port.DataSourceKind
import dev.agentle.feature.hub.sources.DataSourcesRoute
import dev.agentle.feature.hub.timeline.TimelineRoute
import dev.agentle.feature.hub.timeline.TimelineViewModel

/** Registers Dashboard, Data Sources, Permission Center and Timeline. */
public fun EntryProviderScope<NavKey>.hubEntries(navigator: AppNavigator) {
    entry<AppRoute.Dashboard> {
        DashboardRoute(viewModel = hiltViewModel(), navigate = navigator::navigate)
    }
    entry<AppRoute.DataSources> {
        DataSourcesRoute(
            viewModel = hiltViewModel(),
            onBack = navigator::back,
            onOpen = { item ->
                val wearable = item.kind == DataSourceKind.WEARABLE
                navigator.navigate(if (wearable) AppRoute.Wearable else AppRoute.PermissionCenter(item.capabilityIds.firstOrNull()))
            },
        )
    }
    entry<AppRoute.PermissionCenter> { key ->
        PermissionCenterRoute(
            viewModel = hiltViewModel<PermissionCenterViewModel, PermissionCenterViewModel.Factory>(
                creationCallback = { factory -> factory.create(key.capabilityId) },
            ),
            onBack = navigator::back,
        )
    }
    entry<AppRoute.Timeline> { key ->
        TimelineRoute(
            viewModel = hiltViewModel<TimelineViewModel, TimelineViewModel.Factory>(creationCallback = { factory -> factory.create(key) }),
            onBack = navigator::back,
        )
    }
}
