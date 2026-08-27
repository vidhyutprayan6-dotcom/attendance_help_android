package attendance.help.device.webrtc

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class WebRtcModule {

    @Binds
    @Singleton
    abstract fun bindWebRtcSession(impl: PlaceholderWebRtcSession): WebRtcSession
}
