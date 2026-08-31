package com.mekromn.apkbox

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Toast
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
import com.mekromn.apkbox.data.VaultBackupManager
import com.mekromn.apkbox.install.ApkInstaller
import com.mekromn.apkbox.model.ApkProject
import com.mekromn.apkbox.model.ApkRecord
import com.mekromn.apkbox.model.BatchImportItem
import com.mekromn.apkbox.model.BatchImportReport
import com.mekromn.apkbox.model.BatchImportStatus
import com.mekromn.apkbox.model.IconRegenerationOutcome
import com.mekromn.apkbox.model.ReplaceReason
import com.mekromn.apkbox.model.ReplaceRequest
import com.mekromn.apkbox.ui.ApkBoxScreen
import com.mekromn.apkbox.ui.ApkCleanupScreen
import com.mekromn.apkbox.ui.ApkFilePickerScreen
import com.mekromn.apkbox.ui.ApkPickerMode
import com.mekromn.apkbox.ui.BatchResultsScreen
import com.mekromn.apkbox.ui.ProjectsScreen
import com.mekromn.apkbox.ui.SharedApkScreen
import com.mekromn.apkbox.ui.theme.APKboxTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    companion object {
        private const val PREFS_NAME = "apkbox-prefs"
        private const val PREF_LAST_PICKER_DIR = "last-picker-dir"
    }

    private val libraryStore by lazy { LibraryStore(applicationContext) }
    private val apkInstaller by lazy { ApkInstaller(applicationContext, libraryStore) }
    private val backupManager by lazy { VaultBackupManager(applicationContext) }
    private val preferences by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }

    private val busy = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)
    private val replaceRequest = MutableStateFlow<ReplaceRequest?>(null)
    private val pickerMode = MutableStateFlow<ApkPickerMode?>(null)
    private val pickerProjectId = MutableStateFlow<String?>(null)
    private val selectedProjectId = MutableStateFlow<String?>(null)
    private val projectsOverviewRequested = MutableStateFlow(false)
    private val directFileAccess = MutableStateFlow(false)
    private val pendingSharedUris = MutableStateFlow<List<Uri>>(emptyList())
    private val cleanupRequested = MutableStateFlow(false)
    private val batchReport = MutableStateFlow<BatchImportReport?>(null)

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

    private val backupRestoreLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) restoreBackupUri(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        directFileAccess.value = hasDirectFileAccess()
        captureSharedIntent(intent)

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
                val sharedUris = pendingSharedUris.collectAsStateWithLifecycle().value
                val cleanupOpen = cleanupRequested.collectAsStateWithLifecycle().value
                val currentBatchReport = batchReport.collectAsStateWithLifecycle().value

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

                when {
                    sharedUris.isNotEmpty() -> {
                        SharedApkScreen(
                            uris = sharedUris,
                            projects = projects,
                            records = records,
                            busy = isBusy,
                            onDismiss = { pendingSharedUris.value = emptyList() },
                            onAddToProject = ::importSharedToProject,
                            onCreateProject = ::createProjectFromShared,
                        )
                    }
                    currentBatchReport != null -> {
                        BatchResultsScreen(
                            report = currentBatchReport,
                            projectName = projects.firstOrNull { it.id == currentBatchReport.projectId }?.name,
                            onDone = { batchReport.value = null },
                        )
                    }
                    cleanupOpen -> {
                        ApkCleanupScreen(
                            projects = projects,
                            records = records,
                            hasDirectFileAccess = hasFileAccess,
                            onRequestFileAccess = ::requestDirectFileAccess,
                            onDismiss = { cleanupRequested.value = false },
                        )
                    }
                    currentPickerMode != null -> {
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
                    }
                    else -> {
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
                                onCleanupApks = { cleanupRequested.value = true },
                                onRegenerateAllIcons = ::regenerateAllIcons,
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
                                onRenameProject = { newName -> renameProject(selectedProject, newName) },
                                onToggleStar = ::toggleStar,
                                onUpdateDetails = ::updateRecordDetails,
                                onRegenerateIcon = ::regenerateRecordIcon,
                                onConfirmReplace = ::confirmReplacement,
                                onCancelReplace = { replaceRequest.value = null },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        captureSharedIntent(intent)
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

    private fun captureSharedIntent(source: Intent?) {
        if (source == null) return
        val uris = when (source.action) {
            Intent.ACTION_SEND -> sharedSingleUri(source)?.let(::listOf).orEmpty()
            Intent.ACTION_SEND_MULTIPLE -> sharedMultipleUris(source)
            else -> emptyList()
        }.filter { it.scheme == "content" || it.scheme == "file" }.distinct()

        if (uris.isNotEmpty()) {
            pickerMode.value = null
            pickerProjectId.value = null
            cleanupRequested.value = false
            batchReport.value = null
            pendingSharedUris.value = uris
        }
    }

    @Suppress("DEPRECATION")
    private fun sharedSingleUri(source: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            source.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            source.getParcelableExtra(Intent.EXTRA_STREAM)
        }

    @Suppress("DEPRECATION")
    private fun sharedMultipleUris(source: Intent): List<Uri> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            source.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        } else {
            source.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
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
        runBatchTask(
            block = { importRevisionBatch(projectId, uris) },
            afterSuccess = {
                projectsOverviewRequested.value = false
                selectedProjectId.value = projectId
            },
        )
    }

    private fun importSharedToProject(project: ApkProject, uris: List<Uri>) {
        runBatchTask(
            block = { importRevisionBatch(project.id, uris) },
            afterSuccess = {
                pendingSharedUris.value = emptyList()
                projectsOverviewRequested.value = false
                selectedProjectId.value = project.id
            },
        )
    }

    private fun createProjectFromShared(uris: List<Uri>) {
        if (uris.isEmpty()) return
        runBatchTask(
            block = {
                val baseUri = uris.first()
                val baseName = displayNameForUri(baseUri)
                val baseResult = libraryStore.importBase(baseUri)
                val reusedPercent = reusePercent(baseResult.record.sizeBytes, baseResult.reusedBytes)
                val baseItem = BatchImportItem(
                    displayName = baseName,
                    status = BatchImportStatus.ADDED,
                    detail = "Created project base · $reusedPercent% vault-wide reused",
                    recordId = baseResult.record.id,
                )
                val remaining = if (uris.size > 1) importRevisionBatch(baseResult.record.projectId, uris.drop(1))
                else BatchImportReport(baseResult.record.projectId, emptyList())
                BatchImportReport(baseResult.record.projectId, listOf(baseItem) + remaining.items)
            },
            afterSuccess = { report ->
                pendingSharedUris.value = emptyList()
                projectsOverviewRequested.value = false
                selectedProjectId.value = report.projectId
            },
        )
    }

    private suspend fun importRevisionBatch(projectId: String, uris: List<Uri>): BatchImportReport {
        val items = ArrayList<BatchImportItem>(uris.size)
        for (uri in uris) {
            val displayName = displayNameForUri(uri)
            runCatching { libraryStore.importRevision(projectId, uri) }
                .onSuccess { result ->
                    items += BatchImportItem(
                        displayName = displayName,
                        status = BatchImportStatus.ADDED,
                        detail = "Stored · ${reusePercent(result.record.sizeBytes, result.reusedBytes)}% vault-wide reused",
                        recordId = result.record.id,
                    )
                }
                .onFailure { failure ->
                    val detail = failure.message?.takeIf { it.isNotBlank() }
                        ?: "Import failed: ${failure::class.java.simpleName}"
                    items += BatchImportItem(
                        displayName = displayName,
                        status = classifyImportFailure(detail),
                        detail = detail,
                    )
                }
        }
        return BatchImportReport(projectId, items)
    }

    private fun classifyImportFailure(detail: String): BatchImportStatus = when {
        detail.contains("already stored", ignoreCase = true) -> BatchImportStatus.ALREADY_STORED
        detail.contains("belongs to", ignoreCase = true) && detail.contains("Choose the matching project", ignoreCase = true) -> BatchImportStatus.WRONG_PROJECT
        else -> BatchImportStatus.FAILED
    }

    private fun reusePercent(sizeBytes: Long, reusedBytes: Long): Long =
        if (sizeBytes <= 0L) 0L else (reusedBytes * 100L / sizeBytes).coerceIn(0L, 100L)

    private fun displayNameForUri(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
        }
    }.getOrNull()?.takeIf { !it.isNullOrBlank() }
        ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        ?: "APK"

    private fun runBatchTask(
        block: suspend () -> BatchImportReport,
        afterSuccess: (BatchImportReport) -> Unit = {},
    ) {
        if (busy.value) return
        lifecycleScope.launch {
            busy.value = true
            message.value = null
            try {
                val report = block()
                afterSuccess(report)
                batchReport.value = report
            } catch (t: Throwable) {
                message.value = t.message?.takeIf { it.isNotBlank() }
                    ?: "Batch import failed: ${t::class.java.simpleName}"
            } finally {
                busy.value = false
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

    private fun renameProject(project: ApkProject, newName: String) {
        runBusyTask {
            libraryStore.renameProject(project.id, newName)
            "Project renamed to $newName"
        }
    }

    private fun toggleStar(record: ApkRecord) {
        runBusyTask {
            libraryStore.setStarred(record.id, !record.starred)
            if (record.starred) "Removed star from ${record.displayName}" else "Starred ${record.displayName}"
        }
    }

    private fun updateRecordDetails(record: ApkRecord, description: String, notes: String) {
        runBusyTask {
            libraryStore.updateRecordDetails(record.id, description, notes)
            "Description and notes saved"
        }
    }

    private fun regenerateRecordIcon(record: ApkRecord) {
        runBusyTask {
            when (libraryStore.regenerateIcon(record.id)) {
                IconRegenerationOutcome.UPDATED -> "Icon regenerated from ${record.displayName}"
                IconRegenerationOutcome.UNCHANGED -> "Icon verified · already current"
                IconRegenerationOutcome.FAILED -> "Android could not extract an icon from ${record.displayName}"
            }
        }
    }

    private fun regenerateAllIcons() {
        if (busy.value) return
        lifecycleScope.launch {
            busy.value = true
            try {
                val summary = libraryStore.regenerateAllIcons()
                Toast.makeText(
                    this@MainActivity,
                    "Icons: ${summary.updated} updated · ${summary.unchanged} unchanged · ${summary.failed} failed",
                    Toast.LENGTH_LONG,
                ).show()
            } catch (t: Throwable) {
                Toast.makeText(
                    this@MainActivity,
                    t.message ?: "Icon regeneration failed.",
                    Toast.LENGTH_LONG,
                ).show()
            } finally {
                busy.value = false
            }
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
                shareDir.listFiles()?.forEach {
                    if (System.currentTimeMillis() - it.lastModified() > 5 * 60 * 1000L) it.delete()
                }
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
        runBusyTask {
            val location = writeBackupToDownloads()
            "Master backup saved · $location"
        }
    }

    private suspend fun writeBackupToDownloads(): String = withContext(Dispatchers.IO) {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val fileName = "APKbox-master-$stamp.apkboxbackup"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/APKbox")
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Android could not create the master backup file.")
            try {
                contentResolver.openOutputStream(uri, "w")?.use { output ->
                    backupManager.writeBackup(output)
                } ?: error("Android could not open the master backup file.")
            } catch (t: Throwable) {
                runCatching { contentResolver.delete(uri, null, null) }
                throw t
            }
            "Downloads/APKbox/$fileName"
        } else {
            val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "APKbox")
            folder.mkdirs()
            val target = uniqueNamedFile(folder, fileName)
            FileOutputStream(target).use { backupManager.writeBackup(it) }
            target.absolutePath
        }
    }

    private fun restoreVault() {
        if (busy.value) return
        backupRestoreLauncher.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed"))
    }

    private fun restoreBackupUri(uri: Uri) {
        if (busy.value) return
        lifecycleScope.launch {
            busy.value = true
            try {
                val summary = withContext(Dispatchers.IO) { backupManager.restoreBackup(uri) }
                Toast.makeText(
                    this@MainActivity,
                    "Restored ${summary.projects} project${if (summary.projects == 1) "" else "s"} · ${summary.records} builds · every APK SHA-256 verified",
                    Toast.LENGTH_LONG,
                ).show()
                recreate()
            } catch (t: Throwable) {
                message.value = t.message?.takeIf { it.isNotBlank() }
                    ?: "Master restore failed. The previous vault was kept."
            } finally {
                busy.value = false
            }
        }
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

    private fun uniqueNamedFile(folder: File, preferredName: String): File {
        var target = File(folder, preferredName)
        if (!target.exists()) return target
        val dot = preferredName.lastIndexOf('.')
        val base = if (dot > 0) preferredName.substring(0, dot) else preferredName
        val extension = if (dot > 0) preferredName.substring(dot) else ""
        var index = 2
        while (target.exists()) {
            target = File(folder, "$base ($index)$extension")
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
