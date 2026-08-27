package attendance.help.device.webrtc

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object WebRtcModule {
    // PeerConnectionManager and SessionController are @Inject @Singleton constructors.
}
