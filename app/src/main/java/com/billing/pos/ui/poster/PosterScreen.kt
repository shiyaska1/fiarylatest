package com.billing.pos.ui.poster

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.billing.pos.data.AppPrefs
import com.billing.pos.data.DownloadSaver
import com.billing.pos.poster.PosterRenderer
import com.billing.pos.poster.PosterSize
import com.billing.pos.poster.PosterSpec
import com.billing.pos.poster.PosterStore
import com.billing.pos.poster.PosterTemplate
import com.billing.pos.ui.common.rememberThumbnail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Accent colours offered for the built-in templates. */
private val ACCENTS = listOf(
    0xFF1565C0.toInt(), 0xFFC62828.toInt(), 0xFF2E7D32.toInt(),
    0xFF6A1B9A.toInt(), 0xFFEF6C00.toInt(), 0xFF00838F.toInt()
)

/**
 * Poster designer for social media.
 *
 * Built-in templates are drawn in code; "My template" draws the text over an image the user
 * imported. Sharing goes through the system chooser, which is what makes every installed
 * social app available without integrating with each one separately.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PosterScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val prefs = remember { AppPrefs(context) }
    val company = remember { prefs.company }

    var template by remember { mutableStateOf(PosterTemplate.BOLD) }
    var size by remember { mutableStateOf(PosterSize.SQUARE) }
    var accent by remember { mutableStateOf(ACCENTS.first()) }
    var headline by remember { mutableStateOf("SPECIAL OFFER") }
    var itemName by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var offer by remember { mutableStateOf("") }
    var footer by remember {
        mutableStateOf(listOf(company.name, company.phone).filter { it.isNotBlank() }.joinToString("  ·  "))
    }
    var photoPath by remember { mutableStateOf<String?>(null) }
    var backgroundPath by remember { mutableStateOf<String?>(null) }

    var templates by remember { mutableStateOf(PosterStore.templates(context)) }
    var preview by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var rendering by remember { mutableStateOf(false) }

    val productPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            // Copy in, so the poster keeps working after the gallery item goes away.
            val f = PosterStore.importTemplate(context, uri)
            photoPath = f?.absolutePath
        }
    }
    val templatePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val f = PosterStore.importTemplate(context, uri)
            templates = PosterStore.templates(context)
            if (f != null) { backgroundPath = f.absolutePath; template = PosterTemplate.CUSTOM }
        }
    }

    val spec = PosterSpec(
        template = template, size = size, headline = headline, itemName = itemName,
        price = price, offer = offer, footer = footer,
        photoPath = photoPath, backgroundPath = backgroundPath, accent = accent
    )

    // Re-render whenever anything changes; off the main thread since it is a 1080px canvas.
    LaunchedEffect(spec) {
        rendering = true
        preview = withContext(Dispatchers.Default) { runCatching { PosterRenderer.render(context, spec) }.getOrNull() }
        rendering = false
    }

    fun exportThen(action: (File) -> Unit) {
        val bmp = preview ?: return
        scope.launch {
            val f = withContext(Dispatchers.IO) { PosterStore.writePoster(context, bmp) }
            if (f == null) snackbar.showSnackbar("Could not create the poster") else action(f)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Poster designer") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(12.dp).verticalScroll(rememberScrollState())
        ) {
            // ---- Preview ----
            Box(
                Modifier.fillMaxWidth().heightIn(min = 220.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                val p = preview
                if (p != null) {
                    Image(
                        p.asImageBitmap(), contentDescription = "Poster preview",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().padding(8.dp)
                    )
                } else if (rendering) CircularProgressIndicator()
            }

            // ---- Share / save ----
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { exportThen { f -> PosterStore.share(context, f, caption(itemName, price, offer, footer)) } },
                    modifier = Modifier.weight(1.4f)
                ) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(" Share")
                }
                OutlinedButton(
                    onClick = { exportThen { f -> PosterStore.share(context, f, caption(itemName, price, offer, footer), "com.whatsapp") } },
                    modifier = Modifier.weight(1f)
                ) { Text("WhatsApp") }
                OutlinedButton(
                    onClick = {
                        exportThen { f ->
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    DownloadSaver.save(context, f, f.name, "image/jpeg")
                                }
                                snackbar.showSnackbar(if (ok) "Saved to Downloads" else "Could not save")
                            }
                        }
                    }
                ) { Icon(Icons.Filled.Download, contentDescription = "Save") }
            }
            Text(
                "Share opens your phone's share sheet — WhatsApp, Instagram, Facebook and any " +
                    "other social app installed appear there.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 4.dp)
            )

            Divider(Modifier.padding(vertical = 12.dp))

            // ---- Template ----
            Text("Template", style = MaterialTheme.typography.titleSmall)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PosterTemplate.values().forEach { t ->
                    val usable = t != PosterTemplate.CUSTOM || backgroundPath != null
                    FilterChip(
                        selected = template == t,
                        onClick = {
                            if (t == PosterTemplate.CUSTOM && backgroundPath == null) {
                                templatePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            } else template = t
                        },
                        enabled = true,
                        label = { Text(if (usable) t.label else "${t.label} (import)") }
                    )
                }
            }

            // ---- Imported templates ----
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("My templates", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = {
                    templatePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(" Import")
                }
            }
            if (templates.isEmpty()) {
                Text(
                    "Import a ready-made poster background (from a designer, Canva, anywhere) and " +
                        "your details are drawn over it.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            } else {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    templates.forEach { f ->
                        val bmp = rememberThumbnail(f.absolutePath, 220)
                        Box(
                            Modifier.size(84.dp).clip(RoundedCornerShape(8.dp))
                                .border(
                                    if (backgroundPath == f.absolutePath) 3.dp else 1.dp,
                                    if (backgroundPath == f.absolutePath) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant,
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable { backgroundPath = f.absolutePath; template = PosterTemplate.CUSTOM }
                        ) {
                            if (bmp != null) {
                                Image(bmp, contentDescription = null, contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize())
                            }
                            IconButton(
                                onClick = {
                                    PosterStore.deleteTemplate(f)
                                    if (backgroundPath == f.absolutePath) backgroundPath = null
                                    templates = PosterStore.templates(context)
                                },
                                modifier = Modifier.align(Alignment.TopEnd).size(24.dp)
                            ) {
                                Icon(Icons.Filled.Delete, "Delete", tint = Color.White, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }

            Divider(Modifier.padding(vertical = 12.dp))

            // ---- Shape + colour ----
            Text("Shape", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PosterSize.values().forEach { s ->
                    FilterChip(selected = size == s, onClick = { size = s }, label = { Text(s.label) })
                }
            }
            Text("Colour", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ACCENTS.forEach { a ->
                    Box(
                        Modifier.size(34.dp).clip(CircleShape).background(Color(a))
                            .border(
                                if (accent == a) 3.dp else 1.dp,
                                if (accent == a) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                CircleShape
                            )
                            .clickable { accent = a }
                    )
                }
            }

            Divider(Modifier.padding(vertical = 12.dp))

            // ---- Content ----
            OutlinedButton(
                onClick = { productPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(if (photoPath == null) "  Add product photo" else "  Change product photo")
            }
            OutlinedTextField(headline, { headline = it }, label = { Text("Headline") },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            OutlinedTextField(itemName, { itemName = it }, label = { Text("Item name") },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            OutlinedTextField(price, { price = it }, label = { Text("Price") },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            OutlinedTextField(offer, { offer = it }, label = { Text("Offer line") },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            OutlinedTextField(footer, { footer = it }, label = { Text("Shop name / phone") },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 24.dp))
        }
    }
}

/** Text posted alongside the image, so the details are readable without opening it. */
private fun caption(item: String, price: String, offer: String, footer: String): String =
    listOf(item, price, offer, footer).filter { it.isNotBlank() }.joinToString("\n")
