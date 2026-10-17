package com.yokhan.gates.app.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.yokhan.gates.app.ui.form.switchValue
import com.yokhan.gates.app.ui.form.textValue
import com.yokhan.gates.app.usb.UsbBridgeBinding
import com.yokhan.gates.shared.ui.form.YokhanFormAction
import com.yokhan.gates.shared.ui.form.YokhanFormEvent
import com.yokhan.gates.shared.ui.form.YokhanFormField
import com.yokhan.gates.shared.ui.form.YokhanFormScreen
import com.yokhan.gates.shared.ui.form.YokhanFormSection
import com.yokhan.gates.shared.ui.form.YokhanFormState
import com.yokhan.gates.shared.ui.form.YokhanFormValidation
import com.yokhan.gates.shared.ui.form.YokhanFormValidationState
import com.yokhan.gates.shared.ui.form.hasBlockingValidationErrors
import com.yokhan.gates.shared.ui.form.withFieldEvent
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore

class UsbBridgeSettingsFragment : ToolbarFragment() {
    private val formState = mutableStateOf<YokhanFormState?>(null)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = requireContext().createYokhanSettingsHost(useComposeContainer = true)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar.setTitle(R.string.usb_bridge_title)
        DataStore.initGlobal()
        formState.value = buildForm()
        mountForm(view)
    }

    override fun onResume() {
        super.onResume()
        formState.value = buildForm(formState.value?.dirty == true)
    }

    private fun mountForm(root: View) {
        root.findViewById<ViewGroup>(R.id.settings).apply {
            removeAllViews()
            addView(
                ComposeView(requireContext()).apply {
                    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    setContent {
                        formState.value?.let { state ->
                            YokhanFormScreen(state = state, onEvent = ::handleEvent)
                        }
                    }
                },
            )
        }
    }

    private fun handleEvent(event: YokhanFormEvent) {
        when (event) {
            YokhanFormEvent.Save -> applySettings()
            YokhanFormEvent.Cancel,
            YokhanFormEvent.Reset,
            -> parentFragmentManager.popBackStack()

            is YokhanFormEvent.ActionInvoked -> openTetherSettings()
            else -> {
                formState.value = formState.value?.withFieldEvent(event)?.let(::validateForm)
            }
        }
    }

    private fun buildForm(dirty: Boolean = false): YokhanFormState {
        val address = UsbBridgeBinding.detectUsbIpv4()
        return validateForm(
            YokhanFormState(
                title = getString(R.string.usb_bridge_title),
                subtitle = getString(R.string.usb_bridge_subtitle),
                dirty = dirty,
                primaryAction = YokhanFormAction.Apply,
                secondaryAction = YokhanFormAction.Cancel,
                sections = listOf(
                    YokhanFormSection(
                        id = "usb",
                        title = getString(R.string.usb_bridge_section).uppercase(),
                        fields = listOf(
                            YokhanFormField.Action(
                                id = FieldTetherSettings,
                                label = getString(R.string.usb_bridge_interface),
                                value = address ?: getString(R.string.usb_bridge_not_detected),
                                actionLabel = getString(R.string.usb_bridge_open_settings),
                            ),
                            YokhanFormField.Switch(
                                id = Key.ENABLE_USB_BRIDGE,
                                label = getString(R.string.usb_bridge_enable),
                                helper = getString(R.string.usb_bridge_enable_summary),
                                checked = DataStore.enableUsbBridge,
                            ),
                            YokhanFormField.Port(
                                id = Key.MIXED_PORT,
                                label = getString(R.string.usb_bridge_port),
                                value = DataStore.mixedPort.toString(),
                            ),
                        ),
                    ),
                ),
            ),
        )
    }

    private fun validateForm(state: YokhanFormState): YokhanFormState {
        val enabled = state.switchValue(Key.ENABLE_USB_BRIDGE)
        val address = UsbBridgeBinding.detectUsbIpv4()
        val port = state.textValue(Key.MIXED_PORT).toIntOrNull()
        return state.copy(
            sections = state.sections.map { section ->
                section.copy(
                    fields = section.fields.map { field ->
                        when (field) {
                            is YokhanFormField.Switch -> if (field.id == Key.ENABLE_USB_BRIDGE) {
                                field.copy(
                                    validation = if (enabled && address == null) {
                                        errorValidation(R.string.usb_bridge_connect_first)
                                    } else {
                                        YokhanFormValidation()
                                    },
                                )
                            } else {
                                field
                            }

                            is YokhanFormField.Port -> field.copy(
                                enabled = enabled,
                                validation = if (port == null || port !in 1..65535) {
                                    errorValidation(R.string.usb_bridge_invalid_port)
                                } else {
                                    YokhanFormValidation()
                                },
                            )

                            else -> field
                        }
                    },
                )
            },
        )
    }

    private fun errorValidation(messageRes: Int): YokhanFormValidation {
        return YokhanFormValidation(
            state = YokhanFormValidationState.Error,
            message = getString(messageRes),
        )
    }

    private fun applySettings() {
        val state = formState.value?.let(::validateForm) ?: return
        formState.value = state
        if (state.hasBlockingValidationErrors()) return

        DataStore.enableUsbBridge = state.switchValue(Key.ENABLE_USB_BRIDGE)
        DataStore.mixedPort = state.textValue(Key.MIXED_PORT).toInt()
        if (DataStore.enableUsbBridge) {
            DataStore.allowAccess = false
        }
        if (DataStore.serviceState.started) {
            SagerNet.reloadServiceAsync()
        }
        parentFragmentManager.popBackStack()
    }

    private fun openTetherSettings() {
        val tetherIntent = Intent(ActionTetherSettings)
        val fallbackIntent = Intent(Settings.ACTION_WIRELESS_SETTINGS)
        runCatching { startActivity(tetherIntent) }
            .onFailure { startActivity(fallbackIntent) }
    }

    private companion object {
        const val FieldTetherSettings = "usbTetherSettings"
        const val ActionTetherSettings = "android.settings.TETHER_SETTINGS"
    }
}
