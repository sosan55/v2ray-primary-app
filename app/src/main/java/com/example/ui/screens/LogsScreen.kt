package com.example.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.LogEntity
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun LogsScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val logs by viewModel.logs.collectAsState()
    val listState = rememberLazyListState()

    var filterQuery by remember { mutableStateOf("") }
    var selectedLevelFilter by remember { mutableStateOf("ALL") } // ALL, INFO, SUCCESS, ERROR

    val filteredLogs = remember(logs, filterQuery, selectedLevelFilter) {
        logs.filter { log ->
            val matchQuery = filterQuery.isEmpty() || log.message.contains(filterQuery, ignoreCase = true) || log.tag.contains(filterQuery, ignoreCase = true)
            val matchLevel = selectedLevelFilter == "ALL" || log.level.uppercase() == selectedLevelFilter.uppercase()
            matchQuery && matchLevel
        }
    }

    // Auto-scroll to top when a new log appears
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SlateDark)
            .padding(16.dp)
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Header Title
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "DIAGNOSTICS",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = SlateTextSecondary,
                    letterSpacing = 1.5.sp
                )
                Text(
                    text = "Routing Terminal",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = SlateTextPrimary
                )
            }

            // Clear log console action
            IconButton(
                onClick = { viewModel.clearLogHistory() },
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = SlateSurface,
                    contentColor = CyberPink
                ),
                modifier = Modifier.testTag("clear_logs_button")
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteSweep,
                    contentDescription = "Clear Terminal logs",
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Tool Filters row
        OutlinedTextField(
            value = filterQuery,
            onValueChange = { filterQuery = it },
            placeholder = { Text("Filter logs...", color = SlateTextSecondary.copy(alpha = 0.5f)) },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("log_search_input"),
            textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, color = SlateTextPrimary),
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search Terminal",
                    tint = SlateTextSecondary,
                    modifier = Modifier.size(16.dp)
                )
            },
            trailingIcon = {
                if (filterQuery.isNotEmpty()) {
                    IconButton(onClick = { filterQuery = "" }) {
                        Icon(imageVector = Icons.Default.Clear, contentDescription = "Clear", tint = SlateTextSecondary)
                    }
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = CyberCyan,
                unfocusedBorderColor = SlateBorder,
                focusedContainerColor = SlateSurface,
                unfocusedContainerColor = SlateSurface
            ),
            shape = RoundedCornerShape(10.dp)
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Filter chips bar list
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("ALL", "INFO", "SUCCESS", "ERROR").forEach { level ->
                val selected = selectedLevelFilter == level
                val chipCol = when (level) {
                    "SUCCESS" -> CyberGreen
                    "ERROR" -> CyberPink
                    "INFO" -> CyberCyan
                    else -> SlateTextPrimary
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) chipCol.copy(alpha = 0.15f) else SlateSurface)
                        .border(
                            1.dp,
                            if (selected) chipCol else SlateBorder,
                            RoundedCornerShape(8.dp)
                        )
                        .clickable { selectedLevelFilter = level }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = level,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (selected) chipCol else SlateTextSecondary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Terminal Output Screen
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(16.dp))
                .border(1.dp, SlateBorder, RoundedCornerShape(16.dp))
                .background(Color.Black)
                .padding(8.dp)
        ) {
            if (filteredLogs.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Terminal,
                            contentDescription = "Terminal details",
                            tint = SlateTextSecondary.copy(alpha = 0.4f),
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Terminal is clean. No logged packets.",
                            fontSize = 11.sp,
                            color = SlateTextSecondary,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(filteredLogs, key = { it.id }) { logItem ->
                        TerminalLogLine(log = logItem)
                    }
                }
            }
        }
    }
}

@Composable
fun TerminalLogLine(log: LogEntity) {
    val dateText = remember(log.timestamp) {
        val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        sdf.format(Date(log.timestamp))
    }

    val themeColor = when (log.level.uppercase()) {
        "SUCCESS" -> CyberGreen
        "ERROR" -> CyberPink
        "WARNING" -> CyberGold
        else -> when (log.tag.uppercase()) {
            "VPN" -> CyberCyan
            "V2RAY-CORE" -> CyberMagenta
            "SUBSCRIPTION" -> CyberGold
            else -> SlateTextSecondary
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Date Monospace Tag
        Text(
            text = dateText,
            color = SlateTextSecondary.copy(alpha = 0.6f),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(end = 8.dp)
        )

        // Source Tag Indicator
        Text(
            text = "[${log.tag}]",
            color = themeColor.copy(alpha = 0.85f),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(end = 8.dp)
        )

        // Contents
        Text(
            text = log.message,
            color = if (log.level == "ERROR") CyberPink else SlateTextPrimary,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            lineHeight = 14.sp,
            modifier = Modifier.weight(1f)
        )
    }
}
