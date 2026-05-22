package im.vector.app.features.home.callhometab

import androidx.appcompat.widget.AppCompatImageView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.airbnb.mvrx.compose.collectAsState
import com.airbnb.mvrx.fragmentViewModel
import im.vector.app.R
import im.vector.app.core.platform.VectorBaseComposeFragment
import im.vector.app.core.ui.compose.ModernPaginationLoader
import im.vector.app.core.utils.ComposeUtils
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.home.HomeActivity
import im.vector.app.features.home.room.detail.timeline.item.CallTileTimelineItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CallScreenComposeUI : VectorBaseComposeFragment() {
    private val viewModel: CallHomeViewModel by fragmentViewModel()

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun ComposeContent() {
        val avatarRenderer = (activity as? HomeActivity)?.avatarRenderer
        val backgroundColor = resolveAndroidAttrColor(android.R.attr.colorBackground)
        val state by viewModel.collectAsState()

        if (avatarRenderer == null) {
            LoadingView()
            return
        }

        Scaffold(
                topBar = {
                    CenterAlignedTopAppBar(
                            title = {
                                Text(
                                        text = "Call Logs",
                                        modifier = Modifier.padding(top = 35.dp),
                                        style = MaterialTheme.typography.titleLarge.copy(
                                                fontFamily = FontFamily(Font(im.vector.lib.ui.styles.R.font.inter_bold))
                                        )
                                )
                            },
                            modifier = Modifier.height(80.dp),
                            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                                    containerColor = ComposeUtils.resolveAndroidAttrColor(im.vector.lib.ui.styles.R.attr.vctr_toolbar_background),
                                    titleContentColor = ComposeUtils.resolveAndroidAttrColor(im.vector.lib.ui.styles.R.attr.vctr_text_primary)
                            )
                    )
                },
                containerColor = backgroundColor
        ) { innerPadding ->
            Surface(
                    color = backgroundColor,
                    modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
            ) {
                when (val uiState = state.uiState) {
                    is CallHomeUiState.Loading -> LoadingView()
                    is CallHomeUiState.Success -> CallHistoryList(
                            backgroundColor,
                            uiState.items,
                            avatarRenderer,
                            state.isPaginating
                    ) {
                        viewModel.handle(CallHomeActions.LoadMoreCallLogs)
                    }
                    is CallHomeUiState.Empty -> EmptyCallHistoryView()
                    is CallHomeUiState.Error -> ErrorCallHistoryView(uiState.message)
                    else -> {}
                }
            }
        }
    }
}


    @Composable
fun CallHistoryList(
        backgroundColor: Color,
        items: List<CallScreenItem>,
        avatarRenderer: AvatarRenderer,
        isPaginating: Boolean,
        onLoadCallLogs: () -> Unit
) {
    val listState = rememberLazyListState()

    LazyColumn(
            modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp),
            state = listState
    ) {
        items(items) { item ->
            CallItemCard(item, avatarRenderer,backgroundColor)
            Spacer(modifier = Modifier.height(2.dp))
        }

        if (isPaginating) {
            item {
                ModernPaginationLoader() // 👈 Show bottom loading
            }
        }
    }

    // Auto trigger pagination
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
                .collect { lastVisible ->
                    if (lastVisible == items.lastIndex) {
                        onLoadCallLogs()
                    }
                }
    }
}


/*@Composable
fun CallItemCard(item: CallScreenItem, avatarRenderer: AvatarRenderer) {
    Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(
                    containerColor = ComposeUtils.resolveAndroidAttrColor(im.vector.lib.ui.styles.R.attr.vctr_message_bubble_outbound)
            ),
            elevation = CardDefaults.elevatedCardElevation(2.dp)
    ) {
        Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
        ) {
            // ✅ Render avatar using AvatarRenderer (Android View)--> will create new compose avatar renderer for future.
            AndroidView(
                    modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape),
                    factory = { context ->
                        AppCompatImageView(context).apply {
                            avatarRenderer.render(item.matrixItem, this)
                        }
                    }
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                        text = item.userName,
                        style = MaterialTheme.typography.bodyLarge,
                        color = ComposeUtils.resolveAndroidAttrColor(im.vector.lib.ui.styles.R.attr.vctr_text_primary)
                )

                val statusText = when (item.callStatus) {
                    CallTileTimelineItem.CallStatus.MISSED -> {
                        if (item.isSentByMe) {
                            stringResource(id = CommonStrings.call_tile_no_answer)
                        } else {
                            if (item.callKind == CallTileTimelineItem.CallKind.AUDIO)
                                stringResource(id = CommonStrings.call_tile_voice_missed)
                            else
                                stringResource(id = CommonStrings.call_tile_video_missed)
                        }
                    }

                    CallTileTimelineItem.CallStatus.REJECTED -> {
                        if (item.isSentByMe) {
                            if (item.callKind == CallTileTimelineItem.CallKind.AUDIO)
                                stringResource(id = CommonStrings.call_tile_voice_declined)
                            else
                                stringResource(id = CommonStrings.call_tile_video_declined)
                        } else {
                            stringResource(id = CommonStrings.call_tile_no_answer)
                        }
                    }

                    CallTileTimelineItem.CallStatus.ENDED -> {
                        if (item.callKind == CallTileTimelineItem.CallKind.VIDEO)
                            stringResource(id = CommonStrings.call_tile_video_call_has_ended, "--:--")
                        else
                            stringResource(id = CommonStrings.call_tile_voice_call_has_ended, "--:--")
                    }

                    CallTileTimelineItem.CallStatus.INVITED -> {
                        if (item.isSentByMe) {
                            stringResource(id = CommonStrings.call_ringing)
                        } else {
                            if (item.callKind == CallTileTimelineItem.CallKind.AUDIO)
                                stringResource(id = CommonStrings.call_tile_voice_incoming)
                            else
                                stringResource(id = CommonStrings.call_tile_video_incoming)
                        }
                    }

                    CallTileTimelineItem.CallStatus.IN_CALL -> {
                        if (item.callKind == CallTileTimelineItem.CallKind.AUDIO)
                            stringResource(id = CommonStrings.call_tile_voice_active)
                        else
                            stringResource(id = CommonStrings.call_tile_video_active)
                    }
                }

                Text(
                        text = "$statusText • ${formatTimestamp(item.timestamp)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = ComposeUtils.resolveAndroidAttrColor(im.vector.lib.ui.styles.R.attr.vctr_content_secondary)
                )

                if (item.callCount > 1) {
                    Text(
                            text = "${item.callCount} times",
                            style = MaterialTheme.typography.labelSmall,
                            color = ComposeUtils.resolveAndroidAttrColor(im.vector.lib.ui.styles.R.attr.vctr_content_secondary)
                    )
                }
            }

            CallIconButton(item.callKind) {
                item.onCallBackClick?.invoke()
            }
        }
    }
}*/

@Composable
fun CallItemCard(item: CallScreenItem, avatarRenderer: AvatarRenderer, backgroundColor:Color) {
    val userNameStatusColour = if (item.callStatus == CallTileTimelineItem.CallStatus.MISSED) {
        ComposeUtils.resolveAndroidAttrColor(im.vector.lib.ui.styles.R.attr.vctr_icon_critical_primary)
    } else {
        ComposeUtils.resolveAndroidAttrColor(im.vector.lib.ui.styles.R.attr.vctr_text_primary)
    }
    Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(
                    containerColor = backgroundColor
            ),
            elevation = CardDefaults.elevatedCardElevation(2.dp)
    ) {
        Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar (keep AndroidView for now)
            AndroidView(
                    modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape),
                    factory = { context ->
                        AppCompatImageView(context).apply {
                            avatarRenderer.render(item.matrixItem, this)
                        }
                    }
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                // User Name TextView
                item.userName?.let {
                    Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily(Font(im.vector.lib.ui.styles.R.font.inter_semi_bold))),
                            color = userNameStatusColour
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Row with direction icon and timestamp
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DirectionIcon(item)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                            text = formatTimestamp(item.timestamp),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily(Font(im.vector.lib.ui.styles.R.font.inter_regular))),
                            color = ComposeUtils.resolveAndroidAttrColor(im.vector.lib.ui.styles.R.attr.vctr_content_secondary)
                    )
                }
            }
            CallIconButton(item.callKind) {
                item.onCallBackClick?.invoke()
            }
        }
    }
}


@Composable
fun DirectionIcon(item: CallScreenItem) {
    val iconRes = if (item.isIncoming) com.android.dialer.dialpadview.R.drawable.quantum_ic_call_received_white_24 else com.android.dialer.dialpadview.R.drawable.quantum_ic_call_made_white_24
    val color = when {
        item.callStatus == CallTileTimelineItem.CallStatus.MISSED -> ComposeUtils.resolveAndroidAttrColor(im.vector.lib.ui.styles.R.attr.vctr_icon_critical_primary)
        item.isIncoming -> ComposeUtils.resolveAndroidAttrColor(im.vector.lib.ui.styles.R.attr.vctr_content_secondary)
        else -> colorResource(im.vector.lib.ui.styles.R.color.palette_element_green_brown)
    }

    Icon(
            painter = painterResource(id = iconRes),
            contentDescription = if (item.isIncoming) "Incoming Call" else "Outgoing Call",
            tint = color,
            modifier = Modifier.size(18.dp)
    )
}

@Composable
fun CallIconButton(callKind: CallTileTimelineItem.CallKind, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        when (callKind) {
            CallTileTimelineItem.CallKind.VIDEO -> Icon(
                    painter = painterResource(id = R.drawable.ic_attachment_video),
                    tint = ComposeUtils.resolveAndroidAttrColor(im.vector.lib.ui.styles.R.attr.vctr_content_secondary),
                    contentDescription = "Video Call"
            )
            CallTileTimelineItem.CallKind.AUDIO -> Icon(
                    imageVector = Icons.Default.Call,
                    tint = ComposeUtils.resolveAndroidAttrColor(im.vector.lib.ui.styles.R.attr.vctr_content_secondary),
                    contentDescription = "Audio Call"
            )
            else -> Icon(
                    imageVector = Icons.Default.Phone,
                    tint = ComposeUtils.resolveAndroidAttrColor(im.vector.lib.ui.styles.R.attr.vctr_content_secondary),
                    contentDescription = "Call"
            )
        }
    }
}

@Composable
fun EmptyCallHistoryView() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("No calls yet", style = MaterialTheme.typography.bodyLarge, color = ComposeUtils.resolveAndroidAttrColor(im.vector.lib.ui.styles.R.attr.vctr_content_primary))
    }
}

@Composable
fun ErrorCallHistoryView(message: String?) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Error: $message", color = MaterialTheme.colorScheme.error)
    }
}

@Composable
fun LoadingView() {
    Box(
            modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
            contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(color = ComposeUtils.resolveAndroidAttrColor(android.R.attr.tint))
    }
}

// helper functions Call--
fun formatTimestamp(timestamp: Long): String {
    val eventDate = Date(timestamp)

    val calNow = java.util.Calendar.getInstance()
    val calEvent = java.util.Calendar.getInstance().apply { time = eventDate }

    val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())

    return when {
        isSameDay(calNow, calEvent) -> "Today • ${timeFormat.format(eventDate)}"
        isYesterday(calNow, calEvent) -> "Yesterday • ${timeFormat.format(eventDate)}"
        else -> {
            val dateFormat = SimpleDateFormat("MMM dd", Locale.getDefault()) // e.g., "Jul 28"
            "${dateFormat.format(eventDate)} • ${timeFormat.format(eventDate)}"
        }
    }
}

private fun isSameDay(cal1: java.util.Calendar, cal2: java.util.Calendar): Boolean {
    return cal1.get(java.util.Calendar.YEAR) == cal2.get(java.util.Calendar.YEAR) &&
            cal1.get(java.util.Calendar.DAY_OF_YEAR) == cal2.get(java.util.Calendar.DAY_OF_YEAR)
}

private fun isYesterday(calNow: java.util.Calendar, calEvent: java.util.Calendar): Boolean {
    calNow.add(java.util.Calendar.DAY_OF_YEAR, -1)
    return isSameDay(calNow, calEvent)
}

