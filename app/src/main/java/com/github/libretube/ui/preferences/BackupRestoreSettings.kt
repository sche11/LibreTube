package com.github.libretube.ui.preferences

import android.os.Bundle
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.preference.Preference
import com.github.libretube.R
import com.github.libretube.helpers.BackupHelper
import com.github.libretube.helpers.ImportHelper
import com.github.libretube.obj.BackupFile
import com.github.libretube.ui.base.BasePreferenceFragment
import com.github.libretube.ui.dialogs.BackupDialog
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class BackupRestoreSettings : BasePreferenceFragment() {
    private val backupDateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH:mm:ss")

    override val titleResourceId: Int = R.string.backup_restore

    // backup and restore database
    private lateinit var getBackupFile: ActivityResultLauncher<String>
    private lateinit var createBackupFile: ActivityResultLauncher<String>
    private var backupFile = BackupFile()

    /**
     * result listeners for importing and exporting subscriptions
     */
    private lateinit var getSubscriptionsFile: ActivityResultLauncher<String>
    private lateinit var createSubscriptionsFile: ActivityResultLauncher<String>

    /**
     * result listeners for importing and exporting playlists
     */
    private lateinit var getPlaylistsFile: ActivityResultLauncher<String>
    private lateinit var createPlaylistsFile: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        getSubscriptionsFile = registerForActivityResult(ActivityResultContracts.GetContent()) {
            ImportHelper.importSubscriptions(requireActivity(), it)
        }
        createSubscriptionsFile = registerForActivityResult(CreateDocument(JSON)) {
            ImportHelper.exportSubscriptions(requireActivity(), it)
        }
        getPlaylistsFile = registerForActivityResult(ActivityResultContracts.GetContent()) {
            ImportHelper.importPlaylists(requireActivity(), it)
        }
        createPlaylistsFile = registerForActivityResult(CreateDocument(JSON)) {
            ImportHelper.exportPlaylists(requireActivity(), it)
        }
        getBackupFile = registerForActivityResult(ActivityResultContracts.GetContent()) {
            BackupHelper.restoreAdvancedBackup(requireContext(), it)
        }
        createBackupFile = registerForActivityResult(CreateDocument(JSON)) {
            BackupHelper.createAdvancedBackup(requireContext(), it, backupFile)
        }

        super.onCreate(savedInstanceState)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.import_export_settings, rootKey)

        val importSubscriptions = findPreference<Preference>("import_subscriptions")
        importSubscriptions?.setOnPreferenceClickListener {
            getSubscriptionsFile.launch("*/*")
            true
        }

        val exportSubscriptions = findPreference<Preference>("export_subscriptions")
        exportSubscriptions?.setOnPreferenceClickListener {
            createSubscriptionsFile.launch("subscriptions.json")
            true
        }

        val importPlaylists = findPreference<Preference>("import_playlists")
        importPlaylists?.setOnPreferenceClickListener {
            getPlaylistsFile.launch("*/*")
            true
        }

        val exportPlaylists = findPreference<Preference>("export_playlists")
        exportPlaylists?.setOnPreferenceClickListener {
            createPlaylistsFile.launch("playlists.json")
            true
        }

        val advancesBackup = findPreference<Preference>("backup")
        advancesBackup?.setOnPreferenceClickListener {
            BackupDialog {
                backupFile = it
                val timestamp = backupDateTimeFormatter.format(LocalDateTime.now())
                createBackupFile.launch("libretube-backup-$timestamp.json")
            }
                .show(childFragmentManager, null)
            true
        }

        val restoreAdvancedBackup = findPreference<Preference>("restore")
        restoreAdvancedBackup?.setOnPreferenceClickListener {
            getBackupFile.launch(JSON)
            true
        }
    }

    companion object {
        private const val JSON = "application/json"
    }
}
