package com.privacyguard.app.ui.statistics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.privacyguard.app.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatisticsScreen(
    viewModel: StatisticsViewModel = viewModel()
) {
    val stats by viewModel.stats.collectAsState()
    val topTrackers by viewModel.topTrackers.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.statistics), fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                StatisticsCard(stringResource(R.string.today), stats.blockedToday, stats.dataSavedToday, stats.trackersToday)
            }
            item {
                StatisticsCard(stringResource(R.string.this_week), stats.blockedWeek, stats.dataSavedWeek, stats.trackersWeek)
            }
            item {
                StatisticsCard(stringResource(R.string.total), stats.blockedTotal, stats.dataSavedTotal, stats.trackersTotal)
            }
            item {
                Text(
                    text = stringResource(R.string.top_blocked_trackers),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            items(topTrackers) { tracker ->
                TrackerRow(tracker)
            }
        }
    }
}

@Composable
private fun StatisticsCard(
    title: String,
    blockedCount: Int,
    dataSaved: String,
    trackersFound: Int
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatisticsValue(blockedCount.toString(), stringResource(R.string.blocked_count), Icons.Default.Block, Color(0xFFF44336))
                StatisticsValue(dataSaved, stringResource(R.string.data_saved), Icons.Default.Save, Color(0xFF4CAF50))
                StatisticsValue(trackersFound.toString(), stringResource(R.string.tracker), Icons.Default.Visibility, Color(0xFFFF9800))
            }
        }
    }
}

@Composable
private fun StatisticsValue(
    value: String,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = color)
        Text(text = label, fontSize = 10.sp, color = Color.Gray)
    }
}

@Composable
private fun TrackerRow(tracker: TrackerStats) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(text = tracker.name, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(text = tracker.company, fontSize = 11.sp, color = Color.Gray)
            }
            Text(
                text = "${tracker.count} ${stringResource(R.string.times)}",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFF44336)
            )
        }
    }
}
