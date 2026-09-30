package com.wledmusic.engine

import android.app.Application
import com.wledmusic.engine.session.SessionStateRepository
import com.wledmusic.engine.settings.SettingsRepository

class WmeApplication : Application() {
    val sessionRepository = SessionStateRepository()
    val settingsRepository by lazy { SettingsRepository(this) }
}
