package com.github.libretube.util

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.annotation.DrawableRes
import androidx.core.app.NotificationCompat
import androidx.core.app.PendingIntentCompat
import androidx.core.content.getSystemService
import androidx.core.graphics.drawable.toBitmap
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import coil.request.ImageRequest
import com.github.libretube.LibreTubeApp.Companion.PLAYER_CHANNEL_NAME
import com.github.libretube.R
import com.github.libretube.constants.IntentData
import com.github.libretube.enums.NotificationId
import com.github.libretube.enums.PlayerEvent
import com.github.libretube.extensions.toMediaMetadataCompat
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.helpers.PlayerHelper
import com.github.libretube.obj.PlayerNotificationData
import com.github.libretube.services.OnClearFromRecentService
import com.github.libretube.ui.activities.MainActivity
import java.util.UUID

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class NowPlayingNotification(
    private val context: Context,
    private val player: ExoPlayer,
    private val notificationType: NowPlayingNotificationType
) {
    private var videoId: String? = null
    private val nManager = context.getSystemService<NotificationManager>()!!

    /**
     * The metadata of the current playing song (thumbnail, title, uploader)
     */
    private var notificationData: PlayerNotificationData? = null

    /**
     * The [MediaSessionCompat] for the [notificationData].
     */
    private lateinit var mediaSession: MediaSessionCompat

    /**
     * The [NotificationCompat.Builder] to load the [mediaSession] content on it.
     */
    private var notificationBuilder: NotificationCompat.Builder? = null

    /**
     * The [Bitmap] which represents the background / thumbnail of the notification
     */
    private var notificationBitmap: Bitmap? = null

    private fun loadCurrentLargeIcon() {
        if (DataSaverMode.isEnabled(context)) return

        if (notificationBitmap == null) {
            enqueueThumbnailRequest {
                createOrUpdateNotification()
            }
        }
    }

    private fun createCurrentContentIntent(): PendingIntent? {
        // starts a new MainActivity Intent when the player notification is clicked
        // it doesn't start a completely new MainActivity because the MainActivity's launchMode
        // is set to "singleTop" in the AndroidManifest (important!!!)
        // that's the only way to launch back into the previous activity (e.g. the player view
        val intent = Intent(context, MainActivity::class.java).apply {
            if (notificationType == NowPlayingNotificationType.AUDIO_ONLINE) {
                putExtra(IntentData.openAudioPlayer, true)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        }

        return PendingIntentCompat
            .getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT, false)
    }

    private fun createIntent(action: String): PendingIntent? {
        val intent = Intent(action)
            .setPackage(context.packageName)

        return PendingIntentCompat
            .getBroadcast(context, 1, intent, PendingIntent.FLAG_CANCEL_CURRENT, false)
    }

    private fun enqueueThumbnailRequest(callback: (Bitmap) -> Unit) {
        // If playing a downloaded file, show the downloaded thumbnail instead of loading an
        // online image
        notificationData?.thumbnailPath?.let { path ->
            ImageHelper.getDownloadedImage(context, path)?.let {
                notificationBitmap = processBitmap(it)
                callback.invoke(notificationBitmap!!)
            }
            return
        }

        val request = ImageRequest.Builder(context)
            .data(notificationData?.thumbnailUrl)
            .target {
                notificationBitmap = processBitmap(it.toBitmap())
                callback.invoke(notificationBitmap!!)
            }
            .build()

        // enqueue the thumbnail loading request
        ImageHelper.imageLoader.enqueue(request)
    }

    private fun processBitmap(bitmap: Bitmap): Bitmap {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            bitmap
        } else {
            ImageHelper.getSquareBitmap(bitmap)
        }
    }

    private val legacyNotificationButtons
        get() = listOf(
            createNotificationAction(R.drawable.ic_prev_outlined, PlayerEvent.Prev.name),
            createNotificationAction(
                if (player.isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                PlayerEvent.PlayPause.name
            ),
            createNotificationAction(R.drawable.ic_next_outlined, PlayerEvent.Next.name),
            createNotificationAction(R.drawable.ic_rewind_md, PlayerEvent.Rewind.name),
            createNotificationAction(R.drawable.ic_forward_md, PlayerEvent.Forward.name)
        )

    private fun createNotificationAction(
        drawableRes: Int,
        actionName: String
    ): NotificationCompat.Action {
        return NotificationCompat.Action.Builder(drawableRes, actionName, createIntent(actionName))
            .build()
    }

    private fun createMediaSessionAction(
        @DrawableRes drawableRes: Int,
        actionName: String
    ): PlaybackStateCompat.CustomAction {
        return PlaybackStateCompat.CustomAction.Builder(actionName, actionName, drawableRes).build()
    }

    /**
     * Creates a [MediaSessionCompat] for the player
     */
    private fun createMediaSession() {
        if (this::mediaSession.isInitialized) return

        val sessionCallback = object : MediaSessionCompat.Callback() {
            override fun onRewind() {
                handlePlayerAction(PlayerEvent.Rewind)
                super.onRewind()
            }

            override fun onFastForward() {
                handlePlayerAction(PlayerEvent.Forward)
                super.onFastForward()
            }

            override fun onPlay() {
                handlePlayerAction(PlayerEvent.PlayPause)
                super.onPlay()
            }

            override fun onPause() {
                handlePlayerAction(PlayerEvent.PlayPause)
                super.onPause()
            }

            override fun onSkipToNext() {
                handlePlayerAction(PlayerEvent.Next)
                super.onSkipToNext()
            }

            override fun onSkipToPrevious() {
                handlePlayerAction(PlayerEvent.Prev)
                super.onSkipToPrevious()
            }

            override fun onStop() {
                handlePlayerAction(PlayerEvent.Stop)
                super.onStop()
            }

            override fun onSeekTo(pos: Long) {
                player.seekTo(pos)
                super.onSeekTo(pos)
            }

            override fun onCustomAction(action: String, extras: Bundle?) {
                runCatching { handlePlayerAction(PlayerEvent.valueOf(action)) }
                super.onCustomAction(action, extras)
            }
        }

        mediaSession = MediaSessionCompat(context, UUID.randomUUID().toString())
        mediaSession.setCallback(sessionCallback)

        updateSessionMetadata()
        updateSessionPlaybackState()

        val playerStateListener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                super.onIsPlayingChanged(isPlaying)
                updateSessionPlaybackState(isPlaying = isPlaying)
            }

            override fun onIsLoadingChanged(isLoading: Boolean) {
                super.onIsLoadingChanged(isLoading)

                if (!isLoading) {
                    updateSessionMetadata()
                }

                updateSessionPlaybackState(isLoading = isLoading)
            }

            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                super.onMediaMetadataChanged(mediaMetadata)
                updateSessionMetadata(mediaMetadata)
            }
        }

        player.addListener(playerStateListener)
    }

    private fun updateSessionMetadata(metadata: MediaMetadata? = null) {
        val data = metadata ?: player.mediaMetadata
        val newMetadata = data.toMediaMetadataCompat(player.duration, notificationBitmap)
        mediaSession.setMetadata(newMetadata)
    }

    private fun updateSessionPlaybackState(isPlaying: Boolean? = null, isLoading: Boolean? = null) {
        val loading = isLoading == true || (isPlaying == false && player.isLoading)

        val newPlaybackState = when {
            loading -> PlaybackStateCompat.STATE_BUFFERING
            isPlaying ?: player.isPlaying -> PlaybackStateCompat.STATE_PLAYING
            else -> PlaybackStateCompat.STATE_PAUSED
        }

        mediaSession.setPlaybackState(createPlaybackState(newPlaybackState))
    }

    private fun createPlaybackState(@PlaybackStateCompat.State state: Int): PlaybackStateCompat {
        val stateActions = PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
            PlaybackStateCompat.ACTION_REWIND or
            PlaybackStateCompat.ACTION_FAST_FORWARD or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_SEEK_TO

        return PlaybackStateCompat.Builder()
            .setActions(stateActions)
            .addCustomAction(createMediaSessionAction(R.drawable.ic_rewind_md, PlayerEvent.Rewind.name))
            .addCustomAction(createMediaSessionAction(R.drawable.ic_forward_md, PlayerEvent.Forward.name))
            .setState(state, player.currentPosition, player.playbackParameters.speed)
            .build()
    }

    /**
     * Forward the action to the responsible notification owner (e.g. PlayerFragment)
     */
    private fun handlePlayerAction(action: PlayerEvent) {
        val intent = Intent(PlayerHelper.getIntentActionName(context))
            .setPackage(context.packageName)
            .putExtra(PlayerHelper.CONTROL_TYPE, action)
        context.sendBroadcast(intent)
    }

    /**
     * Updates or creates the [notificationBuilder]
     */
    fun updatePlayerNotification(videoId: String, data: PlayerNotificationData) {
        this.videoId = videoId
        this.notificationData = data
        // reset the thumbnail bitmap in order to become reloaded for the new video
        this.notificationBitmap = null

        loadCurrentLargeIcon()

        if (notificationBuilder == null) {
            createMediaSession()
            createNotificationBuilder()
            // update the notification each time the player continues playing or pauses
            player.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    createOrUpdateNotification()
                    super.onIsPlayingChanged(isPlaying)
                }
            })
            context.startService(Intent(context, OnClearFromRecentService::class.java))
        }

        createOrUpdateNotification()
    }

    /**
     * Initializes the [notificationBuilder] attached to the [player] and shows it.
     */
    private fun createNotificationBuilder() {
        notificationBuilder = NotificationCompat.Builder(context, PLAYER_CHANNEL_NAME)
            .setSmallIcon(R.drawable.ic_launcher_lockscreen)
            .setContentIntent(createCurrentContentIntent())
            .setDeleteIntent(createIntent(PlayerEvent.Stop.name))
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(1)
            )
    }

    private fun createOrUpdateNotification() {
        if (notificationBuilder == null) return
        val notification = notificationBuilder!!
            .setContentTitle(notificationData?.title)
            .setContentText(notificationData?.uploaderName)
            .setLargeIcon(notificationBitmap)
            .clearActions()
            .apply {
                legacyNotificationButtons.forEach {
                    addAction(it)
                }
            }
            .build()
        updateSessionMetadata()
        nManager.notify(NotificationId.PLAYER_PLAYBACK.id, notification)
    }

    /**
     * Destroy the [NowPlayingNotification]
     */
    fun destroySelf() {
        if (this::mediaSession.isInitialized) mediaSession.release()

        nManager.cancel(NotificationId.PLAYER_PLAYBACK.id)
    }

    fun cancelNotification() {
        nManager.cancel(NotificationId.PLAYER_PLAYBACK.id)
    }

    fun refreshNotification() {
        createOrUpdateNotification()
    }

    companion object {
        enum class NowPlayingNotificationType {
            VIDEO_ONLINE,
            VIDEO_OFFLINE,
            AUDIO_ONLINE,
            AUDIO_OFFLINE
        }
    }
}
