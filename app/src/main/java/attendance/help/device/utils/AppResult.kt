package attendance.help.device.utils

/**
 * Shared result wrapper for domain / data boundaries.
 * Keeps UI free of raw exceptions while preserving error messages.
 */
sealed class AppResult<out T> {
    data class Success<T>(val data: T) : AppResult<T>()
    data class Error(
        val message: String,
        val cause: Throwable? = null
    ) : AppResult<Nothing>()
}
