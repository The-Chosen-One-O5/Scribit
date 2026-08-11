package com.thechosenone.scribit

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.FileProvider
import com.thechosenone.scribit.ui.ScribitApp
import com.thechosenone.scribit.ui.ScribitViewModel
import com.thechosenone.scribit.worker.ExpiryWorker
import java.io.File

class MainActivity : ComponentActivity() {
    private val viewModel: ScribitViewModel by viewModels()
    private var pendingCameraFile: File? = null

    private val openDocuments = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.importUris(uris)
    }

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val file = pendingCameraFile
        pendingCameraFile = null
        if (success && file != null) viewModel.importCameraFile(file) else file?.delete()
    }

    private val requestNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ExpiryWorker.schedule(this)
        if (Build.VERSION.SDK_INT >= 33) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)

        setContent {
            ScribitApp(
                viewModel = viewModel,
                onImport = { openDocuments.launch(arrayOf("application/pdf", "image/*", "text/*")) },
                onScan = { launchCamera() }
            )
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    private fun launchCamera() {
        val dir = File(cacheDir, "camera").apply { mkdirs() }
        val file = File(dir, "scan-${System.currentTimeMillis()}.jpg")
        pendingCameraFile = file
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        takePicture.launch(uri)
    }

    @Suppress("DEPRECATION")
    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val uris = mutableListOf<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> {
                (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let(uris::add)
                intent.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(uris::add) }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let(uris::addAll)
                intent.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(uris::add) }
            }
        }
        if (uris.isNotEmpty()) viewModel.importUris(uris.distinct())
    }
}
