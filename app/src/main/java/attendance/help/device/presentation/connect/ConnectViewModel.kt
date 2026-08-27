package attendance.help.device.presentation.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import attendance.help.device.domain.model.AppLinkSnapshot
import attendance.help.device.domain.model.ServerLinkState
import attendance.help.device.utils.TailscaleIpFinder
import attendance.help.device.webrtc.SessionController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ConnectForm(
    val host: String = "",
    val name: String = "Phone",
    val hostLocally: Boolean = false,
    val detectedIp: String? = TailscaleIpFinder.findPreferredIp()
)

@HiltViewModel
class ConnectViewModel @Inject constructor(
    private val sessionController: SessionController
) : ViewModel() {

    private val form = MutableStateFlow(ConnectForm())

    val ui: StateFlow<Pair<ConnectForm, AppLinkSnapshot>> = combine(
        form,
        sessionController.uiState
    ) { f, s -> f to s }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ConnectForm() to AppLinkSnapshot()
    )

    fun onHostChange(v: String) = form.updateCopy { copy(host = v) }
    fun onNameChange(v: String) = form.updateCopy { copy(name = v) }
    fun onHostLocallyChange(v: Boolean) = form.updateCopy { copy(hostLocally = v) }

    fun connect() {
        val f = form.value
        viewModelScope.launch {
            sessionController.connectToServer(
                hostInput = if (f.hostLocally) f.detectedIp.orEmpty() else f.host,
                hostLocally = f.hostLocally,
                displayName = f.name
            )
        }
    }

    private fun MutableStateFlow<ConnectForm>.updateCopy(block: ConnectForm.() -> ConnectForm) {
        value = value.block()
    }

    fun isConnected(state: AppLinkSnapshot): Boolean =
        state.serverLinkState == ServerLinkState.CONNECTED ||
            state.serverLinkState == ServerLinkState.HOSTING_AND_CONNECTED
}
