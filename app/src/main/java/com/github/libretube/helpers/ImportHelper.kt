package com.github.libretube.helpers

import android.app.Activity
import android.net.Uri
import android.util.Log
import com.github.libretube.R
import com.github.libretube.api.JsonHelper
import com.github.libretube.api.PlaylistsHelper
import com.github.libretube.api.RetrofitInstance
import com.github.libretube.api.SubscriptionHelper
import com.github.libretube.db.DatabaseHolder.Database
import com.github.libretube.enums.ImportFormat
import com.github.libretube.extensions.TAG
import com.github.libretube.extensions.toastFromMainDispatcher
import com.github.libretube.obj.FreeTubeImportPlaylist
import com.github.libretube.obj.FreetubeSubscription
import com.github.libretube.obj.FreetubeSubscriptions
import com.github.libretube.obj.NewPipeSubscription
import com.github.libretube.obj.NewPipeSubscriptions
import com.github.libretube.obj.PipedImportPlaylist
import com.github.libretube.obj.PipedPlaylistFile
import com.github.libretube.ui.dialogs.ShareDialog
import com.github.libretube.util.TextUtils
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.util.stream.Collectors

object ImportHelper {
    /**
     * Import subscriptions by a file uri
     */
    suspend fun importSubscriptions(activity: Activity, uri: Uri, importFormat: ImportFormat) {
        try {
            SubscriptionHelper.importSubscriptions(getChannelsFromUri(activity, uri, importFormat))
            activity.toastFromMainDispatcher(R.string.importsuccess)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG(), e.toString())
            val type = activity.contentResolver.getType(uri)
            val message = activity.getString(R.string.unsupported_file_format, type)
            activity.toastFromMainDispatcher(message)
        } catch (e: Exception) {
            Log.e(TAG(), e.toString())
            e.localizedMessage?.let {
                activity.toastFromMainDispatcher(it)
            }
        }
    }

    /**
     * Get a list of channel IDs from a file [Uri]
     */
    @OptIn(ExperimentalSerializationApi::class)
    private fun getChannelsFromUri(
        activity: Activity,
        uri: Uri,
        importFormat: ImportFormat
    ): List<String> {
        return when (importFormat) {
            ImportFormat.NEWPIPE -> {
                val subscriptions = activity.contentResolver.openInputStream(uri)?.use {
                    JsonHelper.json.decodeFromStream<NewPipeSubscriptions>(it)
                }
                subscriptions?.subscriptions.orEmpty().map {
                    it.url.replace("${ShareDialog.YOUTUBE_FRONTEND_URL}/channel/", "")
                }
            }

            ImportFormat.FREETUBE -> {
                val subscriptions = activity.contentResolver.openInputStream(uri)?.use {
                    JsonHelper.json.decodeFromStream<FreetubeSubscriptions>(it)
                }
                subscriptions?.subscriptions.orEmpty().map {
                    it.url.replace("${ShareDialog.YOUTUBE_FRONTEND_URL}/channel/", "")
                }
            }

            ImportFormat.YOUTUBECSV -> {
                // import subscriptions from Google/YouTube Takeout
                activity.contentResolver.openInputStream(uri)?.use {
                    it.bufferedReader().use { reader ->
                        reader.lines().map { line -> line.substringBefore(",") }
                            .filter { channelId -> channelId.length == 24 }
                            .collect(Collectors.toList())
                    }
                }.orEmpty()
            }

            else -> throw IllegalArgumentException()
        }
    }

    /**
     * Write the text to the document
     */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun exportSubscriptions(activity: Activity, uri: Uri, importFormat: ImportFormat) {
        val token = PreferenceHelper.getToken()
        val subs = if (token.isNotEmpty()) {
            RetrofitInstance.authApi.subscriptions(token)
        } else {
            val subscriptions = Database.localSubscriptionDao().getAll().map { it.channelId }
            RetrofitInstance.authApi.unauthenticatedSubscriptions(subscriptions)
        }

        when (importFormat) {
            ImportFormat.NEWPIPE -> {
                val newPipeChannels = subs.map {
                    NewPipeSubscription(it.name, 0, "${ShareDialog.YOUTUBE_FRONTEND_URL}${it.url}")
                }
                val newPipeSubscriptions = NewPipeSubscriptions(subscriptions = newPipeChannels)
                activity.contentResolver.openOutputStream(uri)?.use {
                    JsonHelper.json.encodeToStream(newPipeSubscriptions, it)
                }
            }

            ImportFormat.FREETUBE -> {
                val freeTubeChannels = subs.map {
                    FreetubeSubscription(
                        it.name,
                        "",
                        "${ShareDialog.YOUTUBE_FRONTEND_URL}${it.url}"
                    )
                }
                val freeTubeSubscriptions = FreetubeSubscriptions(subscriptions = freeTubeChannels)
                activity.contentResolver.openOutputStream(uri)?.use {
                    JsonHelper.json.encodeToStream(freeTubeSubscriptions, it)
                }
            }

            else -> throw IllegalArgumentException()
        }

        activity.toastFromMainDispatcher(R.string.exportsuccess)
    }

    /**
     * Import Playlists
     */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun importPlaylists(activity: Activity, uri: Uri, importFormat: ImportFormat) {
        val importPlaylists = mutableListOf<PipedImportPlaylist>()

        when (importFormat) {
            ImportFormat.PIPED -> {
                val playlistFile = activity.contentResolver.openInputStream(uri)?.use {
                    JsonHelper.json.decodeFromStream<PipedPlaylistFile>(it)
                }
                importPlaylists.addAll(playlistFile?.playlists.orEmpty())

                // convert the YouTube URLs to videoIds
                importPlaylists.forEach { playlist ->
                    playlist.videos = playlist.videos.map { it.takeLast(11) }
                }
            }

            ImportFormat.FREETUBE -> {
                val playlistFile = activity.contentResolver.openInputStream(uri)?.use {
                    JsonHelper.json.decodeFromStream<List<FreeTubeImportPlaylist>>(it)
                }
                val playlists = playlistFile?.map { playlist ->
                    // convert FreeTube videos to list of string
                    // convert FreeTube playlists to piped playlists
                    PipedImportPlaylist(
                        playlist.name,
                        null,
                        null,
                        playlist.videos.map { it.videoId }
                    )
                }
                importPlaylists.addAll(playlists.orEmpty())
            }

            ImportFormat.YOUTUBECSV -> {
                val playlist = PipedImportPlaylist()
                activity.contentResolver.openInputStream(uri)?.use { inputStream ->
                    val lines = inputStream.bufferedReader().use { reader ->
                        reader.lines().collect(Collectors.toList())
                    }
                    // invalid playlist file, hence returning
                    if (lines.size < 2) return

                    val playlistName = lines[1].split(",").reversed().getOrNull(2)
                    // the playlist name can be undefined in some cases, e.g. watch later lists
                    playlist.name = playlistName ?: TextUtils.defaultPlaylistName

                    // start directly at the beginning if header playlist info such as name is missing
                    val startIndex = if (playlistName == null) {
                        1
                    } else {
                        // seek to the first blank line
                        var splitIndex = lines.indexOfFirst { line -> line.isBlank() }
                        while (lines.getOrElse(splitIndex) { return }.isBlank()) splitIndex++
                        // skip the line containing the names of the columns
                        splitIndex + 2
                    }
                    for (line in lines.subList(startIndex, lines.size)) {
                        if (line.isBlank()) continue

                        line.split(",")
                            .firstOrNull()
                            ?.takeIf { it.isNotBlank() }
                            ?.let { videoId ->
                                playlist.videos += videoId.trim()
                            }
                    }
                    importPlaylists.add(playlist)
                }

                // convert the YouTube URLs to videoIds
                importPlaylists.forEach { importPlaylist ->
                    importPlaylist.videos = importPlaylist.videos.map { it.takeLast(11) }
                }
            }

            else -> throw IllegalArgumentException()
        }
        try {
            PlaylistsHelper.importPlaylists(importPlaylists)
            activity.toastFromMainDispatcher(R.string.success)
        } catch (e: Exception) {
            Log.e(TAG(), e.toString())
            e.localizedMessage?.let {
                activity.toastFromMainDispatcher(it)
            }
        }
    }

    /**
     * Export Playlists
     */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun exportPlaylists(activity: Activity, uri: Uri, importFormat: ImportFormat) {
        when (importFormat) {
            ImportFormat.PIPED -> {
                val playlists = PlaylistsHelper.exportPipedPlaylists()
                val playlistFile = PipedPlaylistFile(playlists = playlists)

                activity.contentResolver.openOutputStream(uri)?.use {
                    JsonHelper.json.encodeToStream(playlistFile, it)
                }
                activity.toastFromMainDispatcher(R.string.exportsuccess)
            }

            ImportFormat.FREETUBE -> {
                val playlists = PlaylistsHelper.exportFreeTubePlaylists()

                activity.contentResolver.openOutputStream(uri)?.use {
                    JsonHelper.json.encodeToStream(playlists, it)
                }
                activity.toastFromMainDispatcher(R.string.exportsuccess)
            }

            else -> Unit
        }
    }
}
