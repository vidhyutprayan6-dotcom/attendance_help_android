package attendance.help.device.utils

import android.os.Build

object DeviceHints {
    /** True on LDPlayer, Android Emulator, Genymotion, BlueStacks, etc. */
    fun isProbablyEmulator(): Boolean {
        val fingerprint = Build.FINGERPRINT.lowercase()
        val model = Build.MODEL.lowercase()
        val hardware = Build.HARDWARE.lowercase()
        val product = Build.PRODUCT.lowercase()
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()
        val device = Build.DEVICE.lowercase()
        val board = Build.BOARD.lowercase()
        return fingerprint.contains("generic") ||
            fingerprint.contains("unknown") ||
            fingerprint.contains("emulator") ||
            model.contains("emulator") ||
            model.contains("ldplayer") ||
            model.contains("android sdk built for") ||
            model.contains("virtual") ||
            hardware.contains("goldfish") ||
            hardware.contains("ranchu") ||
            hardware.contains("vbox") ||
            hardware.contains("ttvm") ||
            hardware.contains("nox") ||
            product.contains("sdk") ||
            product.contains("emulator") ||
            product.contains("vbox") ||
            manufacturer.contains("genymotion") ||
            manufacturer.contains("tencent") ||
            brand.contains("generic") ||
            device.contains("generic") ||
            device.contains("vbox") ||
            board.contains("unknown") ||
            // Common LDPlayer / x86 emulator markers
            (hardware.contains("x86") && fingerprint.contains("test-keys")) ||
            Build.HOST.lowercase().contains("buildserver")
    }
}
