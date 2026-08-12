package com.thechosenone.scribit.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import com.thechosenone.scribit.BuildConfig
import com.thechosenone.scribit.data.AppSettings
import com.thechosenone.scribit.data.DocumentRecord
import com.thechosenone.scribit.data.QueueStats
import kotlinx.coroutines.delay
import org.json.JSONArray
import java.io.File
import java.text.DateFormat
import java.util.Date

private val ScribitLightColors = lightColorScheme(
    primary = Color(0xFF6558E8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8E5FF),
    onPrimaryContainer = Color(0xFF201A5B),
    secondary = Color(0xFF625F72),
    secondaryContainer = Color(0xFFE8E4F2),
    background = Color(0xFFF8F7FC),
    surface = Color(0xFFFFFBFF),
    surfaceVariant = Color(0xFFF0EEF7),
    onSurface = Color(0xFF1C1B20),
    onSurfaceVariant = Color(0xFF66616F),
    outline = Color(0xFF817C8A),
    outlineVariant = Color(0xFFD0CCD8),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6)
)

private val ScribitDarkColors = darkColorScheme(
    primary = Color(0xFFC8C2FF),
    onPrimary = Color(0xFF322A79),
    primaryContainer = Color(0xFF473F9F),
    onPrimaryContainer = Color(0xFFE6E1FF),
    secondary = Color(0xFFC9C4D4),
    secondaryContainer = Color(0xFF474351),
    background = Color(0xFF111116),
    surface = Color(0xFF18171D),
    surfaceVariant = Color(0xFF24222B),
    onSurface = Color(0xFFE7E1E8),
    onSurfaceVariant = Color(0xFFCAC4D0),
    outline = Color(0xFF938E99),
    outlineVariant = Color(0xFF49454F),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A)
)

private val ScribitShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun ScribitApp(
    viewModel: ScribitViewModel,
    onImport: () -> Unit,
    onScan: () -> Unit,
    onExportBackup: () -> Unit,
    onRestoreBackup: () -> Unit
) {
    val settings by viewModel.settings
    val selected by viewModel.selectedDocument
    val message by viewModel.message
    var showSettings by remember { mutableStateOf(false) }
    val snackbarHost = remember { SnackbarHostState() }

    val darkTheme = when (settings.themeMode) {
        AppSettings.THEME_LIGHT -> false
        AppSettings.THEME_DARK -> true
        else -> isSystemInDarkTheme()
    }

    MaterialTheme(
        colorScheme = if (darkTheme) ScribitDarkColors else ScribitLightColors,
        shapes = ScribitShapes
    ) {
        val view = LocalView.current
        SideEffect {
            val activity = view.context as? Activity
            if (activity != null) {
                activity.window.statusBarColor = if (darkTheme) 0xFF111116.toInt() else 0xFFF8F7FC.toInt()
                activity.window.navigationBarColor = if (darkTheme) 0xFF111116.toInt() else 0xFFF8F7FC.toInt()
                WindowCompat.getInsetsController(activity.window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
        }
        LaunchedEffect(message) {
            message?.let {
                snackbarHost.showSnackbar(it)
                viewModel.consumeMessage()
            }
        }

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbarHost) }
        ) { padding ->
            Box(Modifier.padding(padding)) {
                when {
                    !settings.isConfigured -> SetupScreen(
                        initial = settings,
                        viewModel = viewModel,
                        firstRun = true,
                        onExportBackup = onExportBackup,
                        onRestoreBackup = onRestoreBackup
                    )
                    showSettings -> SetupScreen(
                        initial = settings,
                        viewModel = viewModel,
                        firstRun = false,
                        onBack = { showSettings = false },
                        onExportBackup = onExportBackup,
                        onRestoreBackup = onRestoreBackup
                    )
                    selected != null -> DocumentDetailScreen(
                        document = selected!!,
                        viewModel = viewModel,
                        onBack = { viewModel.select(null) }
                    )
                    else -> HomeScreen(
                        viewModel = viewModel,
                        onImport = onImport,
                        onScan = onScan,
                        onSettings = { showSettings = true }
                    )
                }
            }
        }
    }
}

@Composable
private fun ScribitMark(size: Int = 48) {
    Surface(
        modifier = Modifier.size(size.dp),
        shape = RoundedCornerShape((size * 0.30f).dp),
        color = MaterialTheme.colorScheme.primary
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Default.Description,
                contentDescription = null,
                modifier = Modifier.size((size * 0.52f).dp),
                tint = MaterialTheme.colorScheme.onPrimary
            )
            Icon(
                Icons.Default.AutoAwesome,
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding((size * 0.12f).dp)
                    .size((size * 0.22f).dp),
                tint = MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}

@Composable
private fun SetupScreen(
    initial: AppSettings,
    viewModel: ScribitViewModel,
    firstRun: Boolean,
    onExportBackup: () -> Unit,
    onRestoreBackup: () -> Unit,
    onBack: (() -> Unit)? = null
) {
    var baseUrl by remember(initial.apiBaseUrl) { mutableStateOf(initial.apiBaseUrl) }
    var apiKey by remember(initial.apiKey) { mutableStateOf(initial.apiKey) }
    var model by remember(initial.model) { mutableStateOf(initial.model) }
    var vision by remember(initial.supportsVision) { mutableStateOf(initial.supportsVision) }
    var feedback by remember { mutableStateOf<String?>(null) }
    val busy by viewModel.busy

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!firstRun && onBack != null) {
                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                        IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                    }
                    Spacer(Modifier.width(12.dp))
                }
                ScribitMark(if (firstRun) 58 else 46)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (firstRun) "Meet Scribit" else "Scribit settings",
                        style = if (firstRun) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (firstRun) "A quiet little document brain that lives on your phone."
                        else "AI provider, appearance, and privacy controls.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (firstRun) {
            item {
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                            Icon(Icons.Default.Restore, null, Modifier.padding(10.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Already used Scribit?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("Restore your documents and metadata before entering your API key.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = onRestoreBackup,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Default.Restore, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(7.dp))
                        Text("Restore Scribit backup")
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Backups restore your archive, metadata and non-secret settings. Your API key is never stored in the backup, so you will enter it again on this device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            SectionCard {
                Text("Connect your AI", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Use any OpenAI-compatible chat API. Scribit only sends content when a document needs classification or you use Smart Search.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))

                RoundedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = "API base URL",
                    placeholder = "https://api.example.com/v1"
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("API key") },
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    visualTransformation = PasswordVisualTransformation()
                )
                Spacer(Modifier.height(12.dp))
                RoundedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = "Model",
                    placeholder = "your-chat-or-vision-model"
                )
                Spacer(Modifier.height(14.dp))

                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Vision support", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Turn this on if your model can understand images. PDFs are rendered as page images for classification.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(checked = vision, onCheckedChange = { vision = it })
                    }
                }

                Spacer(Modifier.height(16.dp))
                val candidate = AppSettings(
                    apiBaseUrl = baseUrl.trim(),
                    apiKey = apiKey.trim(),
                    model = model.trim(),
                    supportsVision = vision,
                    themeMode = initial.themeMode,
                    libraryLayout = initial.libraryLayout
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = {
                            feedback = null
                            viewModel.testSettings(candidate) { ok, text ->
                                feedback = text
                                if (ok) viewModel.saveSettings(candidate)
                            }
                        },
                        enabled = !busy && candidate.isConfigured,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        if (busy) {
                            CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Wifi, null, Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(7.dp))
                        Text("Test")
                    }
                    Button(
                        onClick = {
                            viewModel.saveSettings(candidate)
                            if (!firstRun) onBack?.invoke()
                        },
                        enabled = candidate.isConfigured,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Default.Lock, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(7.dp))
                        Text(if (firstRun) "Save & enter" else "Save")
                    }
                }

                feedback?.let { text ->
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (text.startsWith("Connected")) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
                    ) {
                        Text(
                            text,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            color = if (text.startsWith("Connected")) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }

        item {
            SectionCard {
                Text("Appearance", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Follow your phone, stay light, or stay dark.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                ThemeChooser(selected = initial.themeMode, onSelect = viewModel::setThemeMode)
            }
        }

        if (!firstRun) {
            item {
                SectionCard {
                    Text("Backup & restore", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "Keep a portable copy of Scribit's archive and metadata before changing phones or doing a reinstall.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = onExportBackup,
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(Icons.Default.Save, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(7.dp))
                            Text("Back up")
                        }
                        OutlinedButton(
                            onClick = onRestoreBackup,
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(Icons.Default.Restore, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(7.dp))
                            Text("Restore")
                        }
                    }
                    Spacer(Modifier.height(9.dp))
                    Text(
                        "The backup includes Scribit's document copies, local metadata and non-secret settings. API keys are intentionally excluded.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                        Icon(Icons.Default.Info, null, Modifier.padding(10.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("About Scribit", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "Version ${BuildConfig.VERSION_NAME} · build ${BuildConfig.VERSION_CODE}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        item {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                        Icon(Icons.Default.Lock, null, Modifier.padding(10.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Privacy by design", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("No always-running daemon. No AI-controlled file moving.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(12.dp))
                PrivacyLine("Your API key is encrypted with Android Keystore.")
                PrivacyLine("Scribit keeps its own private archive copy; the original stays untouched.")
                PrivacyLine("Background work wakes only when needed, then goes back to sleep.")
                PrivacyLine("Low-confidence AI results land in Needs Review instead of being silently trusted.")
            }
        }
    }
}

@Composable
private fun ThemeChooser(selected: String, onSelect: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        listOf(
            AppSettings.THEME_SYSTEM to "System",
            AppSettings.THEME_LIGHT to "Light",
            AppSettings.THEME_DARK to "Dark"
        ).forEach { (value, label) ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                label = { Text(label) }
            )
        }
    }
}

@Composable
private fun PrivacyLine(text: String) {
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Default.CheckCircle, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(9.dp))
        Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shadowElevation = 1.dp
    ) {
        Column(Modifier.padding(18.dp), content = content)
    }
}

@Composable
private fun RoundedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String = ""
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = { if (placeholder.isNotBlank()) Text(placeholder) },
        singleLine = true,
        shape = RoundedCornerShape(18.dp)
    )
}

@Composable
private fun HomeScreen(
    viewModel: ScribitViewModel,
    onImport: () -> Unit,
    onScan: () -> Unit,
    onSettings: () -> Unit
) {
    val documents by viewModel.documents
    val busy by viewModel.busy
    val query by viewModel.searchQuery
    val category by viewModel.categoryFilter
    val reviewOnly by viewModel.reviewOnly
    val queueStats by viewModel.queueStats
    val availableCategories by viewModel.categories
    val libraryLayout = viewModel.settings.value.libraryLayout
    var layoutMenuExpanded by remember { mutableStateOf(false) }
    var showAddCategory by remember { mutableStateOf(false) }
    var newCategoryName by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<DocumentRecord?>(null) }
    var pendingManage by remember { mutableStateOf<DocumentRecord?>(null) }

    if (showAddCategory) {
        AlertDialog(
            onDismissRequest = { showAddCategory = false; newCategoryName = "" },
            icon = { Icon(Icons.Default.CreateNewFolder, null) },
            title = { Text("Add a category", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        "Give it a simple name. Scribit will include it in future AI classifications, and you can assign it by hand too.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = newCategoryName,
                        onValueChange = { newCategoryName = it.take(32) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Category name") },
                        placeholder = { Text("Personal") },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp)
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddCategory = false; newCategoryName = "" }) { Text("Cancel") }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.addCategory(newCategoryName)
                        showAddCategory = false
                        newCategoryName = ""
                    },
                    enabled = newCategoryName.trim().isNotBlank(),
                    shape = RoundedCornerShape(14.dp)
                ) { Text("Add") }
            }
        )
    }

    pendingDelete?.let { document ->
        val displayName = document.title.ifBlank { document.originalName }
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            icon = { Icon(Icons.Default.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Delete document?", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "\"$displayName\" will be removed from Scribit, including Scribit's private archived copy. " +
                        "The original file you imported from elsewhere is not touched."
                )
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteDocument(document)
                        pendingDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.Delete, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Delete")
                }
            }
        )
    }

    pendingManage?.let { document ->
        var chosen by remember(document.id, document.categories, availableCategories) {
            mutableStateOf(document.categories.toSet())
        }
        AlertDialog(
            onDismissRequest = { pendingManage = null },
            icon = { Icon(Icons.Default.CheckCircle, null) },
            title = { Text("Manage categories", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        document.title.ifBlank { document.originalName },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(8.dp))
                    availableCategories.forEach { categoryName ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    chosen = if (categoryName in chosen) chosen - categoryName else chosen + categoryName
                                }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = categoryName in chosen,
                                onCheckedChange = { checked ->
                                    chosen = if (checked) chosen + categoryName else chosen - categoryName
                                }
                            )
                            Text(categoryName, modifier = Modifier.weight(1f))
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = {
                        pendingManage = null
                        pendingDelete = document
                    }) {
                        Icon(Icons.Default.DeleteOutline, null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(6.dp))
                        Text("Delete document", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingManage = null }) { Text("Cancel") }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.setDocumentCategories(document.id, chosen.toList())
                    pendingManage = null
                }) {
                    Icon(Icons.Default.Check, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Save")
                }
            }
        )
    }

    LaunchedEffect(queueStats.activeCount > 0) {
        while (queueStats.activeCount > 0) {
            delay(2000)
            viewModel.refresh()
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ScribitMark(48)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Scribit", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("Your documents, minus the mess.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                        IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") }
                    }
                }
            }

            item {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 1.dp,
                    shadowElevation = 1.dp
                ) {
                    Column(Modifier.padding(14.dp)) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { viewModel.searchQuery.value = it },
                            modifier = Modifier.fillMaxWidth(),
                            leadingIcon = { Icon(Icons.Default.Search, null) },
                            placeholder = { Text("Search titles, tags, organisations…") },
                            singleLine = true,
                            shape = RoundedCornerShape(18.dp),
                            trailingIcon = {
                                if (query.isNotBlank()) {
                                    IconButton(onClick = { viewModel.searchQuery.value = ""; viewModel.applySearch() }) {
                                        Icon(Icons.Default.Close, "Clear")
                                    }
                                }
                            }
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { viewModel.applySearch() },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Icon(Icons.Default.Search, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(7.dp))
                                Text("Search")
                            }
                            FilledTonalButton(
                                onClick = { viewModel.smartSearch(query) },
                                enabled = query.isNotBlank() && !busy,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(7.dp))
                                Text("Smart Search")
                            }
                        }
                    }
                }
            }

            if (queueStats.activeCount > 0) {
                item { QueueStatusCard(queueStats) }
            }

            item {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Library", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text("${documents.size} shown", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        Box {
                            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                                IconButton(onClick = { layoutMenuExpanded = true }) {
                                    Icon(
                                        when (libraryLayout) {
                                            AppSettings.LAYOUT_GRID -> Icons.Default.GridView
                                            AppSettings.LAYOUT_COMPACT -> Icons.Default.Reorder
                                            else -> Icons.Default.ViewList
                                        },
                                        contentDescription = "Change library layout"
                                    )
                                }
                            }
                            DropdownMenu(
                                expanded = layoutMenuExpanded,
                                onDismissRequest = { layoutMenuExpanded = false }
                            ) {
                                LibraryLayoutMenuItem(
                                    label = "List",
                                    icon = Icons.Default.ViewList,
                                    selected = libraryLayout == AppSettings.LAYOUT_LIST
                                ) {
                                    viewModel.setLibraryLayout(AppSettings.LAYOUT_LIST)
                                    layoutMenuExpanded = false
                                }
                                LibraryLayoutMenuItem(
                                    label = "Compact",
                                    icon = Icons.Default.Reorder,
                                    selected = libraryLayout == AppSettings.LAYOUT_COMPACT
                                ) {
                                    viewModel.setLibraryLayout(AppSettings.LAYOUT_COMPACT)
                                    layoutMenuExpanded = false
                                }
                                LibraryLayoutMenuItem(
                                    label = "Grid",
                                    icon = Icons.Default.GridView,
                                    selected = libraryLayout == AppSettings.LAYOUT_GRID
                                ) {
                                    viewModel.setLibraryLayout(AppSettings.LAYOUT_GRID)
                                    layoutMenuExpanded = false
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(selected = category == null && !reviewOnly, onClick = { viewModel.setCategory(null) }, label = { Text("All") })
                        FilterChip(
                            selected = reviewOnly,
                            onClick = { viewModel.setReviewOnly(!reviewOnly) },
                            label = { Text("Needs review") },
                            leadingIcon = { Icon(Icons.Default.WarningAmber, null, Modifier.size(16.dp)) }
                        )
                        availableCategories.forEach { c ->
                            FilterChip(
                                selected = category == c,
                                onClick = { viewModel.setCategory(if (category == c) null else c) },
                                label = { Text(c) }
                            )
                        }
                        AssistChip(
                            onClick = { showAddCategory = true },
                            label = { Text("Add More") },
                            leadingIcon = { Icon(Icons.Default.Add, null, Modifier.size(16.dp)) }
                        )
                    }
                }
            }

            if (documents.isEmpty()) {
                item { EmptyLibrary(onImport, onScan) }
            } else {
                when (libraryLayout) {
                    AppSettings.LAYOUT_GRID -> {
                        items(documents.chunked(2), key = { row -> row.joinToString("-") { it.id.toString() } }) { row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                row.forEach { document ->
                                    DocumentGridCard(
                                        document = document,
                                        modifier = Modifier.weight(1f),
                                        onClick = { viewModel.select(document) },
                                        onLongClick = { pendingManage = document }
                                    )
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                    AppSettings.LAYOUT_COMPACT -> {
                        items(documents, key = { it.id }) { document ->
                            DocumentCompactCard(
                                document = document,
                                onClick = { viewModel.select(document) },
                                onLongClick = { pendingManage = document }
                            )
                        }
                    }
                    else -> {
                        items(documents, key = { it.id }) { document ->
                            DocumentCard(
                                document = document,
                                onClick = { viewModel.select(document) },
                                onLongClick = { pendingManage = document }
                            )
                        }
                    }
                }
            }
        }

        Column(
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SmallFloatingActionButton(onClick = onScan, shape = RoundedCornerShape(16.dp)) {
                Icon(Icons.Default.PhotoCamera, contentDescription = "Scan")
            }
            ExtendedFloatingActionButton(
                onClick = onImport,
                shape = RoundedCornerShape(18.dp),
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Add document", fontWeight = FontWeight.SemiBold) }
            )
        }
    }
}

@Composable
private fun QueueStatusCard(stats: QueueStats) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = if (stats.retrying > 0) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (stats.processing > 0) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Default.Schedule, null, Modifier.size(24.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (stats.retrying > 0) "AI queue is cooling down" else "AI processing queue",
                    fontWeight = FontWeight.Bold
                )
                val parts = buildList {
                    if (stats.processing > 0) add("${stats.processing} processing")
                    if (stats.queued > 0) add("${stats.queued} waiting")
                    if (stats.retrying > 0) add("${stats.retrying} auto-retrying")
                }
                Text(
                    parts.joinToString(" · ").ifBlank { "Working in the background" },
                    style = MaterialTheme.typography.bodySmall
                )
                if (stats.retrying > 0 && stats.nextRetryAt > 0) {
                    Text(
                        "Next automatic attempt around ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(stats.nextRetryAt))}",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

private fun retryDescription(document: DocumentRecord): String {
    val whenText = if (document.retryAt > System.currentTimeMillis()) {
        " around ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(document.retryAt))}"
    } else {
        " shortly"
    }
    return "The provider rate-limited or temporarily rejected the request. Scribit will retry$whenText; you don't need to tap Retry."
}

@Composable
private fun EmptyLibrary(onImport: () -> Unit, onScan: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f)) {
                Icon(Icons.Default.FolderOpen, null, Modifier.padding(14.dp).size(36.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(14.dp))
            Text("Nothing filed yet", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Drop in a PDF, image, or scan. Scribit will classify it in the background, then get out of your way.",
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onImport, shape = RoundedCornerShape(16.dp)) { Text("Import file") }
                OutlinedButton(onClick = onScan, shape = RoundedCornerShape(16.dp)) { Text("Scan") }
            }
        }
    }
}

@Composable
private fun LibraryLayoutMenuItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    DropdownMenuItem(
        text = { Text(label, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal) },
        leadingIcon = { Icon(icon, null) },
        trailingIcon = { if (selected) Icon(Icons.Default.Check, "Selected", Modifier.size(18.dp)) },
        onClick = onClick
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DocumentCompactCard(
    document: DocumentRecord,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val title = document.title.ifBlank { document.originalName }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(16.dp),
        color = if (document.duplicateWarning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(
                    categoryIcon(document.primaryCategory),
                    null,
                    Modifier.padding(8.dp).size(18.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val subtitle = listOf(document.organization, document.documentType, categorySummary(document))
                    .firstOrNull { it.isNotBlank() }.orEmpty()
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (document.duplicateWarning) {
                    Text("Exact duplicate", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
            }
            DocumentStatusIcon(document)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DocumentGridCard(
    document: DocumentRecord,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val title = document.title.ifBlank { document.originalName }
    Surface(
        modifier = modifier
            .heightIn(min = 158.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(22.dp),
        color = if (document.duplicateWarning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shadowElevation = 1.dp
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Icon(
                        categoryIcon(document.primaryCategory),
                        null,
                        Modifier.padding(10.dp).size(22.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Spacer(Modifier.weight(1f))
                DocumentStatusIcon(document)
            }
            Spacer(Modifier.height(13.dp))
            Text(
                title,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            val subtitle = listOf(document.organization, document.documentType, categorySummary(document))
                .firstOrNull { it.isNotBlank() }.orEmpty()
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(10.dp))
            if (document.duplicateWarning) {
                Text("Exact duplicate · review", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(3.dp))
            }
            when (document.status) {
                DocumentRecord.STATUS_QUEUED -> Text("Queued", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                DocumentRecord.STATUS_PROCESSING -> Text("Analysing…", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                DocumentRecord.STATUS_RETRYING -> Text("Auto-retrying", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.labelSmall)
                else -> if (document.expiryDate.isNotBlank()) {
                    Text("Expires ${document.expiryDate}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun DocumentStatusIcon(document: DocumentRecord) {
    if (document.duplicateWarning) {
        Icon(Icons.Default.ErrorOutline, "Exact duplicate", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
        return
    }
    when (document.status) {
        DocumentRecord.STATUS_PROCESSING -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        DocumentRecord.STATUS_QUEUED -> Icon(Icons.Default.Schedule, "Queued", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        DocumentRecord.STATUS_RETRYING -> Icon(Icons.Default.Schedule, "Retrying automatically", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.tertiary)
        DocumentRecord.STATUS_REVIEW -> Icon(Icons.Default.WarningAmber, "Needs review", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
        else -> Unit
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DocumentCard(
    document: DocumentRecord,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val title = document.title.ifBlank { document.originalName }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(22.dp),
        color = if (document.duplicateWarning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shadowElevation = 1.dp
    ) {
        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(
                    categoryIcon(document.primaryCategory),
                    null,
                    Modifier.padding(12.dp).size(22.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val subtitle = listOf(document.organization, document.documentType, categorySummary(document))
                    .firstOrNull { it.isNotBlank() }.orEmpty()
                if (subtitle.isNotBlank()) {
                    Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
                if (document.duplicateWarning) {
                    Spacer(Modifier.height(3.dp))
                    Text("Exact duplicate · review", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
                if (document.expiryDate.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text("Expires ${document.expiryDate}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                }
                when (document.status) {
                    DocumentRecord.STATUS_QUEUED -> {
                        Spacer(Modifier.height(3.dp))
                        Text("Waiting for AI analysis", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                    }
                    DocumentRecord.STATUS_PROCESSING -> {
                        Spacer(Modifier.height(3.dp))
                        Text("AI is analysing…", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                    }
                    DocumentRecord.STATUS_RETRYING -> {
                        Spacer(Modifier.height(3.dp))
                        Text("Rate limited · retrying automatically", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (document.duplicateWarning) {
                Icon(Icons.Default.ErrorOutline, "Exact duplicate", tint = MaterialTheme.colorScheme.error)
            } else when (document.status) {
                DocumentRecord.STATUS_PROCESSING -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                DocumentRecord.STATUS_QUEUED -> Icon(Icons.Default.Schedule, "Queued", tint = MaterialTheme.colorScheme.primary)
                DocumentRecord.STATUS_RETRYING -> Icon(Icons.Default.Schedule, "Retrying automatically", tint = MaterialTheme.colorScheme.tertiary)
                DocumentRecord.STATUS_REVIEW -> Icon(Icons.Default.WarningAmber, "Needs review", tint = MaterialTheme.colorScheme.error)
                else -> Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DocumentDetailScreen(document: DocumentRecord, viewModel: ScribitViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val activelyProcessing = document.status == DocumentRecord.STATUS_QUEUED ||
        document.status == DocumentRecord.STATUS_PROCESSING ||
        document.status == DocumentRecord.STATUS_RETRYING
    LaunchedEffect(document.id, activelyProcessing) {
        while (activelyProcessing) {
            delay(2000)
            viewModel.refresh()
        }
    }
    val availableCategories by viewModel.categories
    var editMode by remember(document.id, document.status) { mutableStateOf(document.status == DocumentRecord.STATUS_REVIEW) }
    var title by remember(document.id, document.title) { mutableStateOf(document.title.ifBlank { document.originalName.substringBeforeLast('.') }) }
    var selectedCategories by remember(document.id, document.categories) { mutableStateOf(document.categories.toSet()) }
    var showCategoryPicker by remember(document.id) { mutableStateOf(false) }
    var type by remember(document.id, document.documentType) { mutableStateOf(document.documentType) }
    var organization by remember(document.id, document.organization) { mutableStateOf(document.organization) }
    var issueDate by remember(document.id, document.issueDate) { mutableStateOf(document.issueDate) }
    var expiryDate by remember(document.id, document.expiryDate) { mutableStateOf(document.expiryDate) }
    var tags by remember(document.id, document.tagsJson) { mutableStateOf(jsonTags(document.tagsJson)) }
    var summary by remember(document.id, document.summary) { mutableStateOf(document.summary) }

    if (showCategoryPicker) {
        AlertDialog(
            onDismissRequest = { showCategoryPicker = false },
            icon = { Icon(Icons.Default.Category, null) },
            title = { Text("Choose categories", fontWeight = FontWeight.Bold) },
            text = {
                Column(Modifier.heightIn(max = 430.dp)) {
                    Text(
                        "A document can belong to more than one category.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    availableCategories.forEach { categoryName ->
                        val checked = categoryName in selectedCategories
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedCategories = if (checked) selectedCategories - categoryName else selectedCategories + categoryName
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { isChecked ->
                                    selectedCategories = if (isChecked) selectedCategories + categoryName else selectedCategories - categoryName
                                }
                            )
                            Spacer(Modifier.width(6.dp))
                            Icon(categoryIcon(categoryName), null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(9.dp))
                            Text(categoryName, modifier = Modifier.weight(1f))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Need a new one? Use Add More in the Library first, then it will appear here and AI can use it too.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(onClick = { showCategoryPicker = false }, shape = RoundedCornerShape(14.dp)) { Text("Done") }
            }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                }
                Spacer(Modifier.width(12.dp))
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Icon(categoryIcon(document.primaryCategory), null, Modifier.padding(11.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(document.originalName, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    IconButton(onClick = { editMode = !editMode }) {
                        Icon(if (editMode) Icons.Default.Close else Icons.Default.Edit, if (editMode) "Cancel edit" else "Edit")
                    }
                }
            }
        }

        if (document.duplicateWarning) {
            item {
                Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.errorContainer) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(8.dp))
                            Text("Exact duplicate detected", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Another Scribit document has the exact same file contents. Names and AI metadata are not used for this check. Keep this copy if it is intentional, or go back and long-press it to delete it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = { viewModel.keepDuplicate(document.id) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Default.Check, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Keep this copy")
                        }
                    }
                }
            }
        }

        if (document.status == DocumentRecord.STATUS_QUEUED ||
            document.status == DocumentRecord.STATUS_PROCESSING ||
            document.status == DocumentRecord.STATUS_RETRYING
        ) {
            item {
                Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (document.status == DocumentRecord.STATUS_PROCESSING) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Schedule, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                when (document.status) {
                                    DocumentRecord.STATUS_QUEUED -> "Waiting in the AI queue"
                                    DocumentRecord.STATUS_RETRYING -> "Automatic retry scheduled"
                                    else -> "Scribit is analysing this document"
                                },
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                when (document.status) {
                                    DocumentRecord.STATUS_RETRYING -> retryDescription(document)
                                    DocumentRecord.STATUS_QUEUED -> "No action needed. Scribit processes documents one at a time to avoid API bursts."
                                    else -> "This finishes in the background; you can leave the app."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }
        }

        if (document.status == DocumentRecord.STATUS_REVIEW || document.status == DocumentRecord.STATUS_ERROR) {
            item {
                Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.errorContainer) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.WarningAmber, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Needs a quick human look", fontWeight = FontWeight.Bold)
                        }
                        if (document.errorMessage.isNotBlank()) {
                            Spacer(Modifier.height(5.dp))
                            Text(document.errorMessage, style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(onClick = { viewModel.retry(document.id) }, shape = RoundedCornerShape(14.dp)) {
                            Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Retry AI")
                        }
                    }
                }
            }
        }

        item {
            SectionCard {
                Text(if (editMode) "Edit metadata" else "What Scribit knows", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                EditField("Title", title, editMode) { title = it }
                Spacer(Modifier.height(10.dp))
                CategoryField(
                    selected = selectedCategories.toList(),
                    editMode = editMode,
                    onManage = { showCategoryPicker = true }
                )
                Spacer(Modifier.height(10.dp))
                EditField("Document type", type, editMode) { type = it }
                Spacer(Modifier.height(10.dp))
                EditField("Organization", organization, editMode) { organization = it }
                Spacer(Modifier.height(10.dp))
                EditField("Issue date (YYYY-MM-DD)", issueDate, editMode) { issueDate = it }
                Spacer(Modifier.height(10.dp))
                EditField("Expiry date (YYYY-MM-DD)", expiryDate, editMode) { expiryDate = it }
                Spacer(Modifier.height(10.dp))
                EditField("Tags (comma separated)", tags, editMode) { tags = it }
                Spacer(Modifier.height(10.dp))
                EditField("Summary", summary, editMode, minLines = 3) { summary = it }
            }
        }

        if (!editMode) {
            item {
                SectionCard {
                    Text("Archive details", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    DetailLine("Imported", DateFormat.getDateTimeInstance().format(Date(document.importedAt)))
                    DetailLine("AI confidence", "${(document.confidence * 100).toInt()}%")
                    DetailLine("File", "${document.sizeBytes / 1024} KB · ${document.mimeType}")
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        if (editMode) {
                            viewModel.saveManual(document.id, title, selectedCategories.toList(), type, organization, issueDate, expiryDate, tags, summary)
                            editMode = false
                        } else {
                            val file = File(document.archivePath)
                            val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, document.mimeType)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            try { context.startActivity(intent) } catch (_: ActivityNotFoundException) { }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(if (editMode) Icons.Default.Save else Icons.Default.OpenInNew, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(7.dp))
                    Text(if (editMode) "Save" else "Open file")
                }
                OutlinedButton(
                    onClick = { viewModel.retry(document.id) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("Reclassify")
                }
            }
        }
    }
}

@Composable
private fun CategoryField(
    selected: List<String>,
    editMode: Boolean,
    onManage: () -> Unit
) {
    Text("Categories", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(6.dp))
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selected.isEmpty()) {
            AssistChip(onClick = {}, enabled = false, label = { Text("Uncategorized") })
        } else {
            selected.forEach { category ->
                AssistChip(
                    onClick = {},
                    enabled = false,
                    label = { Text(category) },
                    leadingIcon = { Icon(categoryIcon(category), null, Modifier.size(16.dp)) }
                )
            }
        }
    }
    if (editMode) {
        Spacer(Modifier.height(7.dp))
        OutlinedButton(onClick = onManage, shape = RoundedCornerShape(14.dp)) {
            Icon(Icons.Default.Category, null, Modifier.size(18.dp))
            Spacer(Modifier.width(7.dp))
            Text("Manage categories")
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, modifier = Modifier.weight(0.38f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, modifier = Modifier.weight(0.62f), fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun EditField(label: String, value: String, enabled: Boolean, minLines: Int = 1, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        enabled = enabled,
        minLines = minLines,
        maxLines = if (minLines > 1) 6 else 1,
        shape = RoundedCornerShape(17.dp)
    )
}

private fun categorySummary(document: DocumentRecord): String =
    document.categories.joinToString(" · ").ifBlank { "Uncategorized" }

private fun categoryIcon(category: String) = when (category) {
    "Identity" -> Icons.Default.Badge
    "Education" -> Icons.Default.School
    "Career" -> Icons.Default.Work
    "Finance" -> Icons.Default.AccountBalanceWallet
    "Permits" -> Icons.Default.AssignmentInd
    else -> Icons.Default.Description
}

private fun jsonTags(json: String): String = runCatching {
    val array = JSONArray(json)
    (0 until array.length()).joinToString(", ") { array.optString(it) }
}.getOrDefault("")
