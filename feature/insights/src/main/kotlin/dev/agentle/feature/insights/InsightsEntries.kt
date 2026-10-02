package dev.agentle.feature.insights

import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import dev.agentle.core.ui.navigation.AppNavigator
import dev.agentle.core.ui.navigation.AppRoute
import dev.agentle.core.ui.navigation.JitaiBuilderMode
import dev.agentle.feature.insights.builder.ManualBuilderViewModel
import dev.agentle.feature.insights.builder.ui.ManualBuilderRoute
import dev.agentle.feature.insights.builder.ui.NlBuilderRoute
import dev.agentle.feature.insights.insight.InsightDetailViewModel
import dev.agentle.feature.insights.insight.ui.InsightDetailRoute
import dev.agentle.feature.insights.insight.ui.InsightListRoute
import dev.agentle.feature.insights.intervention.InterventionDetailViewModel
import dev.agentle.feature.insights.intervention.ui.InterventionDetailRoute
import dev.agentle.feature.insights.jitai.JitaiDetailViewModel
import dev.agentle.feature.insights.jitai.JitaiListViewModel
import dev.agentle.feature.insights.jitai.ui.JitaiDetailRoute
import dev.agentle.feature.insights.jitai.ui.JitaiListRoute
import dev.agentle.feature.insights.review.ProposalReviewViewModel
import dev.agentle.feature.insights.review.ui.ProposalReviewRoute

/** Registers the insights, JITAI, builder, proposal review and intervention detail routes (Journeys 5-7). */
public fun EntryProviderScope<NavKey>.insightsEntries(navigator: AppNavigator) {
    entry<AppRoute.Insights> {
        InsightListRoute(viewModel = hiltViewModel(), navigator = navigator, onBack = null)
    }
    entry<AppRoute.InsightDetail> { key ->
        InsightDetailRoute(
            viewModel = hiltViewModel<InsightDetailViewModel, InsightDetailViewModel.Factory>(
                creationCallback = { it.create(key.insightId) },
            ),
            navigator = navigator,
        )
    }
    entry<AppRoute.Jitais> { key ->
        JitaiListRoute(
            viewModel = hiltViewModel<JitaiListViewModel, JitaiListViewModel.Factory>(creationCallback = { it.create(key.tab) }),
            navigator = navigator,
        )
    }
    entry<AppRoute.JitaiDetail> { key ->
        JitaiDetailRoute(
            viewModel = hiltViewModel<JitaiDetailViewModel, JitaiDetailViewModel.Factory>(creationCallback = { it.create(key.jitaiId) }),
            navigator = navigator,
        )
    }
    entry<AppRoute.JitaiBuilder> { key ->
        if (key.mode == JitaiBuilderMode.NATURAL_LANGUAGE && key.editJitaiId == null) {
            NlBuilderRoute(viewModel = hiltViewModel(), navigator = navigator)
        } else {
            ManualBuilderRoute(
                viewModel = hiltViewModel<ManualBuilderViewModel, ManualBuilderViewModel.Factory>(
                    creationCallback = { it.create(key.editJitaiId) },
                ),
                navigator = navigator,
            )
        }
    }
    entry<AppRoute.ProposalReview> { key ->
        ProposalReviewRoute(
            viewModel = hiltViewModel<ProposalReviewViewModel, ProposalReviewViewModel.Factory>(
                creationCallback = { it.create(key.proposalId) },
            ),
            navigator = navigator,
        )
    }
    entry<AppRoute.InterventionDetail> { key ->
        InterventionDetailRoute(
            viewModel = hiltViewModel<InterventionDetailViewModel, InterventionDetailViewModel.Factory>(
                creationCallback = { it.create(key.decisionKey) },
            ),
            navigator = navigator,
        )
    }
}
