package com.hippo.ehviewer.download

import com.ehviewer.core.database.model.CUSTOM_DOWNLOAD_POSITION_STEP
import com.ehviewer.core.database.model.DownloadInfo

internal data class DownloadMove(val gid: Long, val anchorGid: Long, val before: Boolean)

internal data class CustomOrderUpdate(val toIndex: Int, val positions: Map<Long, Long>)

internal fun positionBetween(lower: Long?, upper: Long?): Long? = when {
    lower == null && upper == null -> 0L
    lower == null -> upper!!.takeIf { it >= Long.MIN_VALUE + CUSTOM_DOWNLOAD_POSITION_STEP }?.minus(CUSTOM_DOWNLOAD_POSITION_STEP)
    upper == null -> lower.takeIf { it <= Long.MAX_VALUE - CUSTOM_DOWNLOAD_POSITION_STEP }?.plus(CUSTOM_DOWNLOAD_POSITION_STEP)
    else -> {
        val middle = (lower and upper) + ((lower xor upper) shr 1)
        middle.takeIf { it > lower && it < upper }
    }
}

internal fun spacedDownloadPositions(ordered: List<DownloadInfo>): Map<Long, Long> = ordered.mapIndexed { index, info -> info.gid to index * CUSTOM_DOWNLOAD_POSITION_STEP }.toMap()

internal fun planDownloadMove(
    ordered: List<DownloadInfo>,
    index: Int,
    anchorIndex: Int,
    before: Boolean,
): CustomOrderUpdate {
    val target = if (before) anchorIndex else anchorIndex + 1
    val insertion = if (index < target) target - 1 else target
    if (insertion == index) return CustomOrderUpdate(insertion, emptyMap())

    fun excludingMoved(indexWithoutMoved: Int) = ordered[
        if (indexWithoutMoved < index) indexWithoutMoved else indexWithoutMoved + 1,
    ].downloadInfo.customPosition

    val previous = if (insertion > 0) excludingMoved(insertion - 1) else null
    val next = if (insertion < ordered.lastIndex) excludingMoved(insertion) else null
    val position = positionBetween(previous, next)
    val positions = if (position == null) {
        val reordered = ordered.toMutableList().apply { add(insertion, removeAt(index)) }
        spacedDownloadPositions(reordered)
    } else {
        mapOf(ordered[index].gid to position)
    }
    return CustomOrderUpdate(insertion, positions)
}
