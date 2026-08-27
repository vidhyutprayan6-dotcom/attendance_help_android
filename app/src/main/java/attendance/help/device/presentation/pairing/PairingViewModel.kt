package attendance.help.device.presentation.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import attendance.help.device.domain.model.ConnectionState
import attendance.help.device.domain.model.DeviceRole
import attendance.help.device.domain.repository.SessionRepository
import attendance.help.device.utils.TailscaleIpFinder
import attendance.help.device.webrtc.SessionController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PairingUiState(
    val role: DeviceRole? = null,
    val localIp: String? = null,
    val pairingCode: String? = null,
    val connectionState: ConnectionState = ConnectionState.NOT_PAIRED,
    val statusMessage: String = "",
    val lastError: String? = null,
    val remoteIpInput: String = "",
    val remoteCodeInput: String = ""
)

@HiltViewModel
class PairingViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val sessionController: SessionController
) : ViewModel() {

    private val remoteIp = MutableStateFlow("")
    private val remoteCode = MutableStateFlow("")

    val uiState: StateFlow<PairingUiState> = combine(
        sessionRepository.deviceRole,
        sessionController.uiState,
        remoteIp,
        remoteCode
    ) { role, live, ipInput, codeInput ->
        PairingUiState(
            role = role,
            localIp = live.localIp ?: TailscaleIpFinder.findPreferredIp(),
            pairingCode = live.pairingCode,
            connectionState = live.connectionState,
            statusMessage = live.statusMessage,
            lastError = live.lastError,
            remoteIpInput = ipInput,
            remoteCodeInput = codeInput
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        PairingUiState(localIp = TailscaleIpFinder.findPreferredIp())
    )

    fun refreshIp() {
        viewModelScope.launch {
            // Force UI refresh by touching controller prepare if already waiting.
            val ip = TailscaleIpFinder.findPreferredIp()
            // Re-emit through controller state if available.
            if (uiState.value.role == DeviceRole.CONTROLLER &&
                uiState.value.connectionState == ConnectionState.WAITING_FOR_PEER
            ) {
                sessionController.prepareAsController()
            }
            // no-op otherwise; combine reads TailscaleIpFinder for display fallback
            remoteIp.update { it }
        }
    }

    fun startControllerWaiting() {
        viewModelScope.launch {
            sessionController.prepareAsController()
        }
    }

    fun connectRemote(ip: String, code: String) {
        remoteIp.value = ip
        remoteCode.value = code
        viewModelScope.launch {
            sessionRepository.setPeerDevice(
                attendance.help.device.domain.model.PeerDevice(
                    deviceId = "pending",
                    displayName = "Controller",
                    tailscaleIp = ip.trim(),
                    lastConnectedAtEpochMs = System.currentTimeMillis()
                )
            )
            sessionController.connectAsRemote(ip.trim(), code.trim())
        }
    }

    fun onRemoteIpChange(value: String) {
        remoteIp.value = value
    }

    fun onRemoteCodeChange(value: String) {
        remoteCode.value = value
    }
}
