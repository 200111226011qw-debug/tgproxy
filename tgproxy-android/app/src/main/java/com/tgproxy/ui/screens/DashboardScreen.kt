package com.tgproxy.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tgproxy.model.ProxyEntry
import com.tgproxy.model.RefreshProgress
import com.tgproxy.model.StageDurations
import com.tgproxy.model.Stats
import com.tgproxy.model.TgGateStats
import com.tgproxy.model.TgVerdict
import com.tgproxy.ui.components.HeroStatusCard
import com.tgproxy.ui.components.MetricGrid
import com.tgproxy.ui.components.StageTimeline
import com.tgproxy.ui.components.TgGateSummary
import kotlinx.coroutines.flow.StateFlow

/**
 * 仪表盘页：Hero 大卡 + 指标网格 + 漏斗解释 + 上轮阶段耗时。
 *
 * **progress 以 `StateFlow` 形式传入而不是直接传值**：读点必须留在 Hero 那个
 * `item {}` 内部。如果在 [MainScreen] 或本函数的函数体顶层读，进度每跳一次
 * 就会重组整个页面（进而重跑 LazyColumn 的 content lambda）。传流进去，
 * 订阅点就钉死在卡片上。
 *
 * @param progressFlow 刷新进度流（高频，只给 Hero 卡订阅）
 */
@Composable
fun DashboardScreen(
    fetching: Boolean,
    stats: Stats,
    tgStats: TgGateStats,
    durations: StageDurations,
    lastDurationMs: Long?,
    bestLatencyMs: Int?,
    countdownSec: Int,
    countdownStarted: Boolean,
    progressFlow: StateFlow<RefreshProgress?>,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        // 底部留出 FAB 的高度，否则最后一张卡会被悬浮按钮压住
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            val progress by progressFlow.collectAsStateWithLifecycle()
            HeroStatusCard(
                fetching = fetching,
                progress = progress,
                lastDurationMs = lastDurationMs,
                stats = stats,
                bestLatencyMs = bestLatencyMs,
                countdownSec = countdownSec,
                countdownStarted = countdownStarted,
            )
        }
        item {
            MetricGrid(stats = stats, tgStats = tgStats)
        }
        // 槽位按 hasData 判定：无数据时整个 item 不发射，避免 spacedBy 留出空档
        if (tgStats.hasData) {
            item { TgGateSummary(tgStats = tgStats) }
        }
        if (durations.hasData) {
            item { StageTimeline(durations = durations) }
        }
    }
}

/** 「最优延迟」：只看两关通过且延迟已知的节点；没有则 null。 */
internal fun bestLatencyOf(entries: List<ProxyEntry>): Int? = entries
    .filter { it.tgVerdict == TgVerdict.PASS && it.latencyMs != null }
    .minOfOrNull { it.latencyMs!! }
