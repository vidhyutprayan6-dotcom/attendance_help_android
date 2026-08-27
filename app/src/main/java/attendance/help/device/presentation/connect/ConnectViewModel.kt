package attendance.help.device.presentation.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import attendance.help.device.domain.model.AppLinkSnapshot
import attendance.help.device.domain.model.ServerLinkState
import attendance.help.device.domain.repository.SessionRepository
import attendance.help.device.webrtc.SessionController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ConnectForm(
    val host: String = "",
    val name: String = "Phone"
)

@HiltViewModel
class ConnectViewModel @Inject constructor(
    private val sessionController: SessionController,
    private val sessionRepository: SessionRepository
) : ViewModel() {

    private val form = MutableStateFlow(ConnectForm())

    init {
        viewModelScope.launch {
            val savedHost = sessionRepository.serverHost.first()
            val savedName = sessionRepository.displayName.first()
            form.value = ConnectForm(
                host = savedHost,
                name = savedName.ifBlank { "Phone" }
            )
        }
    }

    val ui: StateFlow<Pair<ConnectForm, AppLinkSnapshot>> = combine(
        form,
        sessionController.uiState
    ) { f, s -> f to s }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ConnectForm() to AppLinkSnapshot()
    )

    fun onHostChange(v: String) {
        form.value = form.value.copy(host = v)
    }

    fun onNameChange(v: String) {
        form.value = form.value.copy(name = v)
    }

    fun connect() {
        val f = form.value
        viewModelScope.launch {
            sessionController.connectToServer(
                hostInput = f.host,
                displayName = f.name
            )
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            sessionController.disconnectServer()
        }
    }
}
