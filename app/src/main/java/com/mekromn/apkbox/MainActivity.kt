package com.mekromn.apkbox

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.mekromn.apkbox.data.ApkInspector
import com.mekromn.apkbox.data.LibraryStore
import com.mekromn.apkbox.install.ApkInstaller
import com.mekromn.apkbox.model.ApkProject
import com.mekromn.apkbox.model.ApkRecord
import com.mekromn.apkbox.model.ReplaceReason
import com.mekromn.apkbox.model.ReplaceRequest
import com.mekromn.apkbox.ui.ApkBoxScreen
import com.mekromn.apkbox.ui.ApkFilePickerScreen
import com.mekromn.apkbox.ui.ApkPickerMode
import com.mekromn.apkbox.ui.ProjectsScreen
import com.mekromn.apkbox.ui.theme.APKboxTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {
    companion object {
        private const val PREFS_NAME = "apkbox-prefs"
        private const val PREF_LAST_PICKER_DIR = "last-picker-dir"
    }

    private val libraryStore by lazy { LibraryStore(applicationContext) }
    private val apkInstaller by lazy { ApkInstaller(applicationContext, libraryStore) }
    private val preferences by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }

    private val busy = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)
    private val replaceRequest = MutableStateFlow<ReplaceRequest?>(null)
    private val pickerMode = MutableStateFlow<ApkPickerMode?>(null)
    private val pickerProjectId = MutableStateFlow<String?>(null)
    private val selectedProjectId = MutableStateFlow<String?>(null)
    private val projectsOverviewRequested = MutableStateFlow(false)
    private val directFileAccess = MutableStateFlow(false)

    private var installWaitingForPermission: ApkRecord? = null
    private var installWaitingForRemoval: ApkRecord? = null

    private val uninstallLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val record = installWaitingForRemoval ?: return@registerForActivityResult
        installWaitingForRemoval = null
        val stillInstalled = ApkInspector.inspectInstalled(this, record.packageName) != null
        if (result.resultCode == Activity.RESULT_OK || !stillInstalled) {
            requestInstall(record)
        } else {
            message.value = "Replacement cancelled · the installed app was left unchanged"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        directFileAccess.value = hasDirectFileAccess()

        setContent {
            APKboxTheme {
                val projects = libraryStore.projects.collectAsStateWithLifecycle().value
                val records = libraryStore.records.collectAsStateWithLifecycle().value
                val stats = libraryStore.stats.collectAsStateWithLifecycle().value
                val isBusy = busy.collectAsStateWithLifecycle().value
                val currentMessage = message.collectAsStateWithLifecycle().value
                val currentReplaceRequest = replaceRequest.collectAsStateWithLifecycle().value
                val currentPickerMode = pickerMode.collectAsStateWithLifecycle().value
                val currentPickerProjectId = pickerProjectId.collectAsStateWithLifecycle().value
                val currentProjectId = selectedProjectId.collectAsStateWithLifecycle().value
                val overviewRequested = projectsOverviewRequested.collectAsStateWithLifecycle().value
                val hasFileAccess = directFileAccess.collectAsStateWithLifecycle().value

                LaunchedEffect(projects, currentProjectId, overviewRequested) {
                    if (currentProjectId != null && projects.none { it.id == currentProjectId }) {
                        selectedProjectId.value = null
                    } else if (!overviewRequested && currentProjectId == null && projects.size == 1) {
                        selectedProjectId.value = projects.single().id
                    }
                }

                val basePicker = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument(),
                ) { uri -> if (uri != null) importBase(uri) }

                val revisionPicker = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenMultipleDocuments(),
                ) { uris ->
                    val projectId = pickerProjectId.value
                    if (uris.isNotEmpty() && projectId != null) importRevisions(projectId, uris)
                }

                if (currentPickerMode != null) {
                    ApkFilePickerScreen(
                        mode = currentPickerMode,
                        initialDirectory = rememberedPickerDirectory(),
                        hasDirectFileAccess = hasFileAccess,
                        storedRecords = records,
                        onRequestFileAccess = ::requestDirectFileAccess,
                        onDismiss = {
                            pickerMode.value = null
                            pickerProjectId.value = null
                        },
                        onPicked = { files ->
                            if (files.isNotEmpty()) {
                                rememberPickerDirectory(files.first().parentFile)
                                val projectId = currentPickerProjectId
                                pickerMode.value = null
                                pickerProjectId.value = null
                                val uris = files.map(::uriForFile)
                                when (currentPickerMode) {
                                    ApkPickerMode.BASE -> importBase(uris.first())
                                    ApkPickerMode.REVISIONS -> if (projectId != null) importRevisions(projectId, uris)
                                }
                            }
                        },
                        onUseSystemPicker = {
                            pickerMode.value = null
                            when (currentPickerMode) {
                                ApkPickerMode.BASE -> basePicker.launch(apkMimeTypes())
                                ApkPickerMode.REVISIONS -> revisionPicker.launch(apkMimeTypes())
                            }
                        },
                    )
                } else {
                    val selectedProject = projects.firstOrNull { it.id == currentProjectId }
                    if (selectedProject == null) {
                        ProjectsScreen(
                            projects = projects,
                            records = records,
                            stats = stats,
                            busy = isBusy,
                            onOpenProject = ::openProject,
                            onNewProject = {
                                pickerProjectId.value = null
                                pickerMode.value = ApkPickerMode.BASE
                            },
                            onBackupVault = ::backupVault,
                            onRestoreVault = ::restoreVault,
                        )
                    } else {
                        ApkBoxScreen(
                            project = selectedProject,
                            records = records.filter { it.projectId == selectedProject.id },
                            globalStats = stats,
                            busy = isBusy,
                            message = currentMessage,
                            replaceRequest = currentReplaceRequest,
                            onMessageShown = { message.value = null },
                            onBackToProjects = {
                                projectsOverviewRequested.value = true
                                selectedProjectId.value = null
                            },
                            onAddRevision = {
                                pickerProjectId.value = selectedProject.id
                                pickerMode.value = ApkPickerMode.REVISIONS
                            },
                            onInstall = ::requestInstall,
                            onExport = ::exportRecord,
                            onShare = ::shareRecord,
                            onDelete = ::deleteRevision,
                            onDeleteProject = { deleteProject(selectedProject) },
                            onConfirmReplace = ::confirmReplacement,
                            onCancelReplace = { replaceRequest.value = null },
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        directFileAccess.value = hasDirectFileAccess()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && packageManager.canRequestPackageInstalls()) {
            installWaitingForPermission?.let { record ->
                installWaitingForPermission = null
                requestInstall(record)
            }
        }
    }

    private fun openProject(project: ApkProject) {
        projectsOverviewRequested.value = false
        selectedProjectId.value = project.id
    }

    private fun apkMimeTypes(): Array<String> = arrayOf(
        "application/vnd.android.package-archive",
        "application/octet-stream",
    )

    private fun hasDirectFileAccess(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

    private fun requestDirectFileAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            message.value = "APKbox's direct browser requires Android 11 or newer on this build."
            return
        }
        val appIntent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        runCatching { startActivity(appIntent) }
            .recoverCatching { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
            .onFailure { message.value = "Android could not open the All files access setting." }
    }

    private fun rememberedPickerDirectory(): File {
        val root = Environment.getExternalStorageDirectory()
        val saved = preferences.getString(PREF_LAST_PICKER_DIR, null)?.let(::File)
        if (saved != null && saved.isDirectory && saved.absolutePath.startsWith(root.absolutePath)) return saved
        return File(root, "Download").takeIf { it.isDirectory } ?: root
    }

    private fun rememberPickerDirectory(directory: File?) {
        if (directory?.isDirectory == true) {
            preferences.edit().putString(PREF_LAST_PICKER_DIR, directory.absolutePath).apply()
        }
    }

    private fun uriForFile(file: File): Uri = FileProvider.getUriForFile(
        this,
        "$packageName.files",
        file,
    )

    private fun importBase(uri: Uri) {
        runBusyTask {
            val result = libraryStore.importBase(uri)
            projectsOverviewRequested.value = false
            selectedProjectId.value = result.record.projectId
            "Project created · ${result.record.label}"
        }
    }

    private fun importRevisions(projectId: String, uris: List<Uri>) {
        runBusyTask {
            var added = 0
            var skipped = 0
            var logicalBytes = 0L
            var reusedBytes = 0L
            var firstFailure: String? = null
            for (uri in uris) {
                runCatching { libraryStore.importRevision(projectId, uri) }
                    .onSuccess { result ->
                        added++
                        logicalBytes += result.record.sizeBytes
                        reusedBytes += result.reusedBytes
                    }
                    .onFailure { failure ->
                        skipped++
                        if (firstFailure == null) firstFailure = failure.message
                    }
            }
            if (added == 0) error(firstFailure ?: "No revisions were imported.")
            val reusedPercent = if (logicalBytes == 0L) 0L
            else (reusedBytes * 100L / logicalBytes).coerceIn(0L, 100L)
            buildString {
                append("Saved $added revision")
                if (added != 1) append('s')
                append(" · $reusedPercent% reused across vault")
                if (skipped > 0) append(" · $skipped skipped")
            }
        }
    }

    private fun deleteRevision(record: ApkRecord) {
        runBusyTask {
            libraryStore.deleteRevision(record.id)
            "${record.displayName} removed · unused chunks cleaned up"
        }
    }

    private fun deleteProject(project: ApkProject) {
        runBusyTask {
            libraryStore.deleteProject(project.id)
            selectedProjectId.value = null
            projectsOverviewRequested.value = true
            "${project.name} deleted · globally shared chunks preserved"
        }
    }

    private fun exportRecord(record: ApkRecord) {
        runBusyTask {
            val location = writeRecordToDownloads(record)
            "Exported exact APK · $location"
        }
    }

    private suspend fun writeRecordToDownloads(record: ApkRecord): String = withContext(Dispatchers.IO) {
        val fileName = sanitizedApkName(record.displayName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/vnd.android.package-archive")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/APKbox")
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Android could not create the export in Downloads.")
            try {
                contentResolver.openOutputStream(uri, "w")?.use { output ->
                    libraryStore.streamApk(record, output)
                } ?: error("Android could not open the export file.")
            } catch (t: Throwable) {
                runCatching { contentResolver.delete(uri, null, null) }
                throw t
            }
            "Downloads/APKbox/$fileName"
        } else {
            val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "APKbox")
            folder.mkdirs()
            val target = uniqueFile(folder, fileName)
            FileOutputStream(target).use { libraryStore.streamApk(record, it) }
            target.absolutePath
        }
    }

    private fun shareRecord(record: ApkRecord) {
        if (busy.value) return
        lifecycleScope.launch {
            busy.value = true
            try {
                val shareDir = File(cacheDir, "share").apply { mkdirs() }
                shareDir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 60 * 60 * 1000L) it.delete() }
                val target = uniqueFile(shareDir, sanitizedApkName(record.displayName))
                withContext(Dispatchers.IO) {
                    FileOutputStream(target).use { libraryStore.streamApk(record, it) }
                }
                val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", target)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/vnd.android.package-archive"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(send, "Share ${record.displayName}"))
            } catch (t: Throwable) {
                message.value = t.message ?: "Could not share this APK."
            } finally {
                busy.value = false
            }
        }
    }

    private fun backupVault() {
        message.value = "Master backup engine is being enabled in this build."
    }

    private fun restoreVault() {
        message.value = "Master restore validation is being enabled in this build."
    }

    private fun sanitizedApkName(name: String): String {
        val clean = name.replace(Regex("[\\/:*?\"<>|]"), "_").trim().ifBlank { "APKbox-export.apk" }
        return if (clean.endsWith(".apk", ignoreCase = true)) clean else "$clean.apk"
    }

    private fun uniqueFile(folder: File, preferredName: String): File {
        var target = File(folder, preferredName)
        if (!target.exists()) return target
        val base = preferredName.removeSuffix(".apk")
        var index = 2
        while (target.exists()) {
            target = File(folder, "$base ($index).apk")
            index++
        }
        return target
    }

    private fun requestInstall(record: ApkRecord) {
        if (busy.value) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            installWaitingForPermission = record
            message.value = "Allow APKbox to install unknown apps, then return here."
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }

        val installed = ApkInspector.inspectInstalled(this, record.packageName)
        if (installed != null) {
            val signingMismatch = installed.signingCertSha256 != null &&
                record.signingCertSha256 != null && installed.signingCertSha256 != record.signingCertSha256
            val reason = when {
                signingMismatch -> ReplaceReason.SIGNATURE_MISMATCH
                installed.versionCode > record.versionCode -> ReplaceReason.DOWNGRADE
                else -> null
            }
            if (reason != null) {
                replaceRequest.value = ReplaceRequest(
                    record = record,
                    installedVersionName = installed.versionName,
                    installedVersionCode = installed.versionCode,
                    reason = reason,
                )
                return
            }
        }
        installRecord(record)
    }

    private fun confirmReplacement() {
        val request = replaceRequest.value ?: return
        replaceRequest.value = null
        installWaitingForRemoval = request.record
        @Suppress("DEPRECATION")
        val uninstallIntent = Intent(
            Intent.ACTION_UNINSTALL_PACKAGE,
            Uri.parse("package:${request.record.packageName}"),
        ).putExtra(Intent.EXTRA_RETURN_RESULT, true)
        runCatching { uninstallLauncher.launch(uninstallIntent) }
            .onFailure { failure ->
                installWaitingForRemoval = null
                message.value = failure.message ?: "Android could not open uninstall confirmation."
            }
    }

    private fun installRecord(record: ApkRecord) {
        runBusyTask {
            apkInstaller.install(record)
            "${record.displayName} reconstructed and verified · continue in Android installer"
        }
    }

    private fun runBusyTask(block: suspend () -> String) {
        if (busy.value) return
        lifecycleScope.launch {
            busy.value = true
            try {
                message.value = block()
            } catch (t: Throwable) {
                message.value = t.message?.takeIf { it.isNotBlank() }
                    ?: "Operation failed: ${t::class.java.simpleName}"
            } finally {
                busy.value = false
            }
        }
    }
}
