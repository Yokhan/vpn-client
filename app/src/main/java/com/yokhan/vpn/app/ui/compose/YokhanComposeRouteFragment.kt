package com.yokhan.vpn.app.ui.compose

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import com.yokhan.vpn.app.ui.ToolbarFragment

interface YokhanServiceStateConsumer {
    fun onShellServiceStateChanged(state: BaseService.State)
}

abstract class YokhanComposeRouteFragment : ToolbarFragment(), YokhanServiceStateConsumer {

    protected val serviceState = mutableStateOf(DataStore.serviceState)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val state = serviceState.value
                Content(spaceActive = state.isRouteSpaceActive())
            }
        }
    }

    override fun onShellServiceStateChanged(state: BaseService.State) {
        serviceState.value = state
    }

    protected fun BaseService.State.isRouteSpaceActive(): Boolean {
        return this == BaseService.State.Connecting ||
            this == BaseService.State.Connected ||
            this == BaseService.State.Stopping
    }

    @Composable
    protected abstract fun Content(spaceActive: Boolean)
}
