package attendance.help.device.di

import attendance.help.device.device.DefaultDeviceIdentityProvider
import attendance.help.device.device.DeviceIdentityProvider
import attendance.help.device.utils.AppDispatchers
import attendance.help.device.utils.DefaultAppDispatchers
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppBindingsModule {

    @Binds
    @Singleton
    abstract fun bindDispatchers(impl: DefaultAppDispatchers): AppDispatchers

    @Binds
    @Singleton
    abstract fun bindDeviceIdentity(impl: DefaultDeviceIdentityProvider): DeviceIdentityProvider
}
