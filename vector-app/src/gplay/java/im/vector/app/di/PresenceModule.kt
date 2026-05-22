package im.vector.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.features.presence.SimplePresenceHandler
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PresenceModule {

    @Provides
    @Singleton
    fun provideSimplePresenceHandler(
        activeSessionHolder: ActiveSessionHolder
    ): SimplePresenceHandler {
        return SimplePresenceHandler(activeSessionHolder)
    }
}
