package attendance.help.device.utils

import kotlin.random.Random

object PairingCodeGenerator {
    fun generate6Digit(): String = Random.nextInt(100000, 999999).toString()
}
