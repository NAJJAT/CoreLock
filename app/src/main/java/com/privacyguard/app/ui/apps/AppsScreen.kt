package com.privacyguard.app.ui.apps

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.privacyguard.app.R

data class AppItem(
    val name: String,
    val packageName: String,
    val isSystem: Boolean,
    val isBlocked: Boolean,
    val trackers: Int,
    val dataUsed: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen() {
    var searchQuery by remember { mutableStateOf("") }
    var showSystemApps by remember { mutableStateOf(true) }

    val allApps = remember {
        listOf(
            AppItem("Chrome", "com.android.chrome", false, false, 12, "245 MB"),
            AppItem("Firefox", "org.mozilla.firefox", false, false, 8, "89 MB"),
            AppItem("WhatsApp", "com.whatsapp", false, false, 4, "1.2 GB"),
            AppItem("Instagram", "com.instagram.android", false, true, 23, "567 MB"),
            AppItem("Facebook", "com.facebook.katana", false, true, 31, "892 MB"),
            AppItem("YouTube", "com.google.android.youtube", false, false, 7, "3.4 GB"),
            AppItem("Gmail", "com.google.android.gm", false, false, 5, "123 MB"),
            AppItem("Maps", "com.google.android.apps.maps", false, false, 9, "78 MB"),
            AppItem("System UI", "android", true, false, 0, "0 MB"),
            AppItem("Google Play", "com.android.vending", true, false, 3, "45 MB")
        )
    }

    val filteredApps = allApps.filter {
        (it.name.contains(searchQuery, ignoreCase = true) ||
                it.packageName.contains(searchQuery, ignoreCase = true)) &&
                (showSystemApps || !it.isSystem)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.apps), fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                actions = {
                    IconButton(onClick = { showSystemApps = !showSystemApps }) {
                        Icon(
                            if (showSystemApps) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = stringResource(R.string.system_apps)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // شريط البحث
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                placeholder = { Text(stringResource(R.string.search_apps)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                shape = RoundedCornerShape(24.dp),
                singleLine = true
            )

            // إحصائيات سريعة
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    StatValue(stringResource(R.string.apps_count), filteredApps.size.toString(), Color(0xFF2196F3))
                    StatValue(stringResource(R.string.blocked_count), filteredApps.count { it.isBlocked }.toString(), Color(0xFFF44336))
                    StatValue(stringResource(R.string.trackers_count), filteredApps.sumOf { it.trackers }.toString(), Color(0xFFFF9800))
                }
            }

            // قائمة التطبيقات
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(filteredApps) { app ->
                    AppCard(app = app)
                }
            }
        }
    }
}

@Composable
fun StatValue(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = color)
        Text(text = label, fontSize = 11.sp, color = Color.Gray)
    }
}

@Composable
fun AppCard(app: AppItem) {
    var isBlocked by remember { mutableStateOf(app.isBlocked) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isBlocked) Color(0xFFF44336).copy(alpha = 0.1f)
            else MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // أيقونة التطبيق
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Apps,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // معلومات التطبيق
            Column(modifier = Modifier.weight(1f)) {
                Text(text = app.name, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text(text = app.packageName, fontSize = 11.sp, color = Color.Gray)
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppChip("${app.trackers} ${stringResource(R.string.trackers_count)}", Color(0xFFFF9800))
                    AppChip(app.dataUsed, Color(0xFF2196F3))
                }
            }

            // زر الحظر
            IconButton(onClick = { isBlocked = !isBlocked }) {
                Icon(
                    imageVector = if (isBlocked) Icons.Default.Block else Icons.Default.LockOpen,
                    contentDescription = null,
                    tint = if (isBlocked) Color(0xFFF44336) else Color(0xFF4CAF50)
                )
            }
        }
    }
}

@Composable
fun AppChip(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = text, fontSize = 11.sp, color = Color.Gray)
    }
}
