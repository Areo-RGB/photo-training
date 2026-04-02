package com.paul.sprintsync.core.repositories

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.paul.sprintsync.core.models.LastRunResult
import com.paul.sprintsync.features.motion_detection.MotionDetectionConfig
import kotlinx.coroutines.flow.first

private val Context.dataStore by preferencesDataStore(name = "sprint_sync_store")

class LocalRepository(
    private val context: Context,
) {
    companion object {
        private val MOTION_CONFIG_KEY = stringPreferencesKey("motion_detection_config_v2")
        private val LAST_RUN_KEY = stringPreferencesKey("last_run_result_v2_nanos")
    }

    suspend fun loadMotionConfig(): MotionDetectionConfig {
        val snapshot = context.dataStore.data.first()
        val encoded = snapshot[MOTION_CONFIG_KEY] ?: return MotionDetectionConfig.defaults()
        return MotionDetectionConfig.fromJsonString(encoded)
    }

    suspend fun saveMotionConfig(config: MotionDetectionConfig) {
        context.dataStore.edit { prefs: MutablePreferences ->
            prefs[MOTION_CONFIG_KEY] = config.toJsonString()
        }
    }

    suspend fun loadLastRun(): LastRunResult? {
        val snapshot = context.dataStore.data.first()
        val encoded = snapshot[LAST_RUN_KEY] ?: return null
        return LastRunResult.fromJsonString(encoded)
    }

    suspend fun saveLastRun(run: LastRunResult) {
        context.dataStore.edit { prefs: MutablePreferences ->
            prefs[LAST_RUN_KEY] = run.toJsonString()
        }
    }

    suspend fun clearLastRun() {
        context.dataStore.edit { prefs: MutablePreferences ->
            prefs.remove(LAST_RUN_KEY)
        }
    }
}
