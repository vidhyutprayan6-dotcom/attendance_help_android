package attendance.help.device.presentation.role

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import attendance.help.device.domain.model.DeviceRole
import attendance.help.device.domain.usecase.SetDeviceRoleUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RoleSelectUiState(
    val selectedRole: DeviceRole? = null,
    val isSaving: Boolean = false
)

@HiltViewModel
class RoleSelectViewModel @Inject constructor(
    private val setDeviceRole: SetDeviceRoleUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(RoleSelectUiState())
    val uiState: StateFlow<RoleSelectUiState> = _uiState.asStateFlow()

    fun selectRole(role: DeviceRole) {
        _uiState.update { it.copy(selectedRole = role) }
    }

    fun confirm(onDone: () -> Unit) {
        val role = _uiState.value.selectedRole ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            setDeviceRole(role)
            _uiState.update { it.copy(isSaving = false) }
            onDone()
        }
    }
}
