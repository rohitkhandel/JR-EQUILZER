package com.jr.equalizer.service

import android.content.ComponentName
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class MediaSessionDetectorService : NotificationListenerService() {
    private val controllerCallbacks = HashMap<String, MediaController.Callback>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        refreshActiveSessions()
    }

    override fun onListenerDisconnected() {
        controllerCallbacks.clear()
        AudioRuntimeStatusRepository.updateDetectedPlayer(null)
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        refreshActiveSessions()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        refreshActiveSessions()
    }

    private fun refreshActiveSessions() {
        try {
            val manager = getSystemService(MediaSessionManager::class.java) ?: return
            val listenerComponent = ComponentName(this, MediaSessionDetectorService::class.java)
            val controllers = manager.getActiveSessions(listenerComponent)
            val playing = controllers.firstOrNull {
                it.playbackState?.state == PlaybackStateCompatBridge.STATE_PLAYING
            }
            val packageName = playing?.packageName
            AudioRuntimeStatusRepository.updateDetectedPlayer(packageName)

            controllers.forEach { controller ->
                val pkg = controller.packageName
                if (pkg !in controllerCallbacks) {
                    val callback = object : MediaController.Callback() {
                        override fun onPlaybackStateChanged(state: android.media.session.PlaybackState?) {
                            refreshActiveSessions()
                        }
                    }
                    controller.registerCallback(callback)
                    controllerCallbacks[pkg] = callback
                }
            }
            val stillActive = controllers.map { it.packageName }.toSet()
            val removed = controllerCallbacks.keys.filter { it !in stillActive }
            removed.forEach { pkg ->
                controllerCallbacks.remove(pkg)
            }
        } catch (error: SecurityException) {
            Log.w(TAG, "Notification listener access is not ready for active media sessions", error)
        } catch (error: Throwable) {
            Log.w(TAG, "Could not inspect active media sessions", error)
        }
    }

    private object PlaybackStateCompatBridge {
        const val STATE_PLAYING = android.media.session.PlaybackState.STATE_PLAYING
    }

    companion object {
        private const val TAG = "JR-MediaSessions"
    }
}
