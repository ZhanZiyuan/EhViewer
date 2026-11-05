package com.ehviewer.shared

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.ehviewer.core.model.GalleryComment
import com.ehviewer.core.model.GalleryCommentList
import com.ehviewer.core.network.GalleryLibrary
import com.ehviewer.core.network.gallery.GalleryFailure
import com.ehviewer.core.network.gallery.GalleryRequestException
import ehviewer.app.shared.generated.resources.Res
import ehviewer.app.shared.generated.resources.gallery_back_details
import ehviewer.app.shared.generated.resources.gallery_comment_edited
import ehviewer.app.shared.generated.resources.gallery_comment_uploader
import ehviewer.app.shared.generated.resources.gallery_comments_empty
import ehviewer.app.shared.generated.resources.gallery_comments_end
import ehviewer.app.shared.generated.resources.gallery_comments_more
import ehviewer.app.shared.generated.resources.gallery_comments_title
import ehviewer.app.shared.generated.resources.gallery_link_open_failed
import ehviewer.app.shared.generated.resources.gallery_refresh
import ehviewer.app.shared.generated.resources.gallery_retry
import ehviewer.app.shared.generated.resources.gallery_time_days
import ehviewer.app.shared.generated.resources.gallery_time_future
import ehviewer.app.shared.generated.resources.gallery_time_hours
import ehviewer.app.shared.generated.resources.gallery_time_minutes
import ehviewer.app.shared.generated.resources.gallery_time_now
import ehviewer.app.shared.generated.resources.gallery_time_yesterday
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** The original detail shows two five-line cards, with the full list in a separate route. */
@Composable
internal fun GalleryCommentPreview(comments: GalleryCommentList, images: GalleryCoverCache<ImageBitmap>, gid: Long, token: String, onOpen: (String?) -> Unit) {
    OutlinedCard(border = galleryCrystalBorder(), modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp)) {
        comments.comments.take(2).forEach { comment ->
            GalleryCommentCard(comment, images, gid, token, maxLines = 5, modifier = Modifier.padding(vertical = 4.dp), onOpen = { onOpen(null) }, onLink = { onOpen(it) })
        }
        val label = when {
            comments.comments.isEmpty() -> Res.string.gallery_comments_empty
            comments.comments.size <= 2 && !comments.hasMore -> Res.string.gallery_comments_end
            else -> Res.string.gallery_comments_more
        }
        Box(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(16.dp)).clickable { onOpen(null) }, contentAlignment = Alignment.Center) { Text(galleryString(label)) }
    }
}

@Composable
internal fun rememberCommentImages(session: GalleryLibrary) = remember(session) {
    GalleryCoverCache<ImageBitmap> { request ->
        val bitmap = decodeGalleryCover(session.content.commentImage(request.key, request.gid, request.token))
        PreviewBitmap(bitmap, bitmap.width, bitmap.height)
    }
}

/** Owned by details so opening an actual reader page preserves loaded comments and scroll. */
internal class GalleryCommentsState(initial: GalleryCommentList) {
    var comments by mutableStateOf(initial)
    var failure by mutableStateOf<GalleryFailure?>(null)
    var loading by mutableStateOf(false)
    var attempt by mutableIntStateOf(0)
    var loaded by mutableStateOf(!initial.hasMore)
    var link by mutableStateOf<String?>(null)
    val list = LazyListState()
}

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
internal fun GalleryCommentsScreen(session: GalleryLibrary, gid: Long, token: String, state: GalleryCommentsState, images: GalleryCoverCache<ImageBitmap>, onPage: (String) -> Unit, onBack: () -> Unit) = with(state) {
    var linkFailed by remember { mutableStateOf(false) }
    val browser = LocalUriHandler.current
    BackHandler(onBack = onBack)
    LaunchedEffect(gid, token, attempt) {
        if (!loaded) {
            loading = true
            failure = null
            try {
                comments = session.content.comments(gid, token)
                loaded = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: GalleryRequestException) {
                failure = error.failure
            } finally {
                loading = false
            }
        }
    }
    LaunchedEffect(link, comments, loading) {
        val target = link ?: return@LaunchedEffect
        if (target.startsWith("#c")) {
            val id = target.removePrefix("#c").toLongOrNull()
            val index = comments.comments.indexOfFirst { it.id == id }
            if (index >= 0) {
                list.scrollToItem(index)
                link = null
            } else if (loaded && !loading) {
                link = null
                linkFailed = true
            }
        } else {
            link = null
            val safe = commentWebLink(target, session.restricted, gid, token)
            if (safe == null) {
                linkFailed = true
            } else {
                val page = runCatching { session.content.pageIndex(safe, gid) }.getOrNull()
                if (page != null) {
                    onPage(safe)
                } else {
                    try {
                        browser.openUri(safe)
                        linkFailed = false
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        linkFailed = true
                    }
                }
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(galleryString(Res.string.gallery_comments_title)) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(GalleryIcons.ArrowBack, galleryString(Res.string.gallery_back_details)) }
        }, actions = {
            IconButton(onClick = {
                loaded = false
                attempt++
            }, enabled = !loading) { Icon(GalleryIcons.Refresh, galleryString(Res.string.gallery_refresh)) }
        })
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        failure?.let {
            GalleryError(it) {
                loaded = false
                attempt++
            }
        }
        if (linkFailed) Text(galleryString(Res.string.gallery_link_open_failed), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
        LazyColumn(state = list, modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
            items(comments.comments, key = { it.id }) { comment ->
                SelectionContainer { GalleryCommentCard(comment, images, gid, token, onOpen = null, onLink = { link = it }) }
            }
            item {
                if (comments.hasMore) {
                    TextButton(onClick = {
                        loaded = false
                        attempt++
                    }, enabled = !loading) { Text(galleryString(Res.string.gallery_comments_more)) }
                } else {
                    Text(galleryString(if (comments.comments.isEmpty()) Res.string.gallery_comments_empty else Res.string.gallery_comments_end), Modifier.fillMaxWidth().padding(vertical = 16.dp))
                }
            }
        }
    }
}

/** Only web URLs can reach a platform opener; anchors are handled by the owned comment list. */
internal fun commentWebLink(value: String, restricted: Boolean, gid: Long, token: String): String? {
    if (value.length !in 1..8192 || value.any { it.isISOControl() } || value.startsWith('#')) return null
    val host = if (restricted) "exhentai.org" else "e-hentai.org"
    val resolved = when {
        value.startsWith("//") -> "https:$value"
        value.startsWith('/') -> "https://$host$value"
        value.startsWith('?') -> "https://$host/g/$gid/$token/$value"
        else -> value
    }
    val url = try {
        Url(resolved)
    } catch (_: Exception) {
        return null
    }
    return url.toString().takeIf { url.protocol in listOf(URLProtocol.HTTPS, URLProtocol.HTTP) && url.host.isNotBlank() && url.user.isNullOrEmpty() && url.password.isNullOrEmpty() }
}

@Composable
private fun GalleryCommentCard(comment: GalleryComment, images: GalleryCoverCache<ImageBitmap>, gid: Long, token: String, maxLines: Int = Int.MAX_VALUE, modifier: Modifier = Modifier, onOpen: (() -> Unit)?, onLink: (String) -> Unit) {
    CommentSurface(onOpen, modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (comment.uploader) galleryString(Res.string.gallery_comment_uploader, comment.user.orEmpty()) else comment.user.orEmpty(), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(commentTime(comment.time), style = MaterialTheme.typography.titleSmall)
        }
        val density = LocalDensity.current
        val inline = comment.body.mapIndexedNotNull { index, part ->
            part.image?.let { source ->
                val key = "image:$index"
                val (width, height) = commentImageDimensions(source)
                key to InlineTextContent(Placeholder(with(density) { width.toFloat().toSp() }, with(density) { height.toFloat().toSp() }, PlaceholderVerticalAlign.AboveBaseline)) {
                    CommentImage(source, part.text, images, gid, token, onLink)
                }
            }
        }.toMap()
        val primary = MaterialTheme.colorScheme.primary
        val secondary = MaterialTheme.colorScheme.onSurfaceVariant
        val edited = if (maxLines == Int.MAX_VALUE && comment.lastEdited != 0L) galleryString(Res.string.gallery_comment_edited, commentTime(comment.lastEdited)) else null
        val text = buildAnnotatedString {
            comment.body.forEachIndexed { index, part ->
                val link = part.link
                val decoration = listOfNotNull(TextDecoration.Underline.takeIf { part.underline }, TextDecoration.LineThrough.takeIf { part.strike })
                withStyle(SpanStyle(fontWeight = FontWeight.Bold.takeIf { part.bold }, fontStyle = FontStyle.Italic.takeIf { part.italic }, textDecoration = if (decoration.isEmpty()) null else TextDecoration.combine(decoration))) {
                    if (part.image != null) {
                        appendInlineContent("image:$index", part.text)
                    } else if (link != null) {
                        withLink(LinkAnnotation.Clickable(link, TextLinkStyles(SpanStyle(color = primary, textDecoration = TextDecoration.Underline))) { onLink(link) }) { append(part.text) }
                    } else {
                        var cursor = 0
                        if (maxLines == Int.MAX_VALUE) {
                            commentPlainUrl.findAll(part.text).forEach { match ->
                                append(part.text.substring(cursor, match.range.first))
                                withLink(LinkAnnotation.Clickable(match.value, TextLinkStyles(SpanStyle(color = primary, textDecoration = TextDecoration.Underline))) { onLink(match.value) }) { append(match.value) }
                                cursor = match.range.last + 1
                            }
                        }
                        append(part.text.substring(cursor))
                    }
                }
            }
            if (maxLines == Int.MAX_VALUE) {
                if (comment.id != 0L && comment.score != 0) {
                    append("  ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 0.8.em, color = secondary)) {
                        append(if (comment.score > 0) "+${comment.score}" else "${comment.score}")
                    }
                }
                if (edited != null) {
                    append("\n\n")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 0.8.em, color = secondary)) { append(edited) }
                }
            }
        }
        // AppCompat's original comment widget resolves to 14sp, integer physical pixels,
        // natural font line spacing and Android font padding (measured in the real app).
        val bodySize = with(density) { 14.sp.toPx().roundToInt().toSp() }
        Text(text, style = MaterialTheme.typography.bodyMedium.copy(fontSize = bodySize, lineHeight = TextUnit.Unspecified, letterSpacing = 0.sp, platformStyle = platformCommentTextStyle()), inlineContent = inline, maxLines = maxLines, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
    }
}

internal expect fun platformCommentTextStyle(): PlatformTextStyle?

private val commentPlainUrl = Regex("(http|https)://[a-z0-9A-Z%-]+(\\.[a-z0-9A-Z%-]+)+(:\\d{1,5})?(/[a-zA-Z0-9-_~:#@!&',;=%/*.?+$\\[\\]()]+)?/?")
private val commentImageSize = Regex("(?:[^-]+-){2}(\\d+)-(\\d+)-[^_]+_([^.]+)")

/** Preserve the original image getter's physical-pixel sizing, bounded before layout. */
internal fun commentImageDimensions(source: String): Pair<Int, Int> {
    val size = commentImageSize.find(source)?.groupValues ?: return 200 to 300
    val width = size[1].toIntOrNull()?.takeIf { it in 1..32768 } ?: return 200 to 300
    val height = size[2].toIntOrNull()?.takeIf { it in 1..32768 } ?: return 200 to 300
    val target = (size[3].toIntOrNull() ?: 200).takeIf { it in 1..2048 } ?: return 200 to 300
    val scale = minOf(target.toFloat() / width, (target / 2 * 3).toFloat() / height)
    return maxOf(1, (width * scale).roundToInt()) to maxOf(1, (height * scale).roundToInt())
}

@Composable
private fun CommentSurface(onOpen: (() -> Unit)?, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    if (onOpen == null) {
        Card(modifier.fillMaxWidth(), content = content)
    } else {
        Card(onClick = onOpen, modifier = modifier.fillMaxWidth(), content = content)
    }
}

@Composable
private fun CommentImage(source: String, description: String, images: GalleryCoverCache<ImageBitmap>, gid: Long, token: String, onLink: (String) -> Unit) {
    var bitmap by remember(source) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(source) { mutableStateOf(false) }
    var attempt by remember(source) { mutableIntStateOf(0) }
    LaunchedEffect(source, attempt) {
        failed = false
        try {
            bitmap = images.get(GalleryCoverRequest(gid, token, source)).value
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: GalleryRequestException) {
            failed = true
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image != null) {
            Image(image, description.takeUnless { it == "\uFFFC" }, Modifier.fillMaxSize().clickable { onLink(source) }, contentScale = ContentScale.Fit)
        } else if (failed) {
            TextButton(onClick = { attempt++ }) { Text(galleryString(Res.string.gallery_retry)) }
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun commentTime(time: Long): String {
    val now = Clock.System.now()
    val diff = now.toEpochMilliseconds() - time
    return when {
        diff < 0 || time <= 0 -> galleryString(Res.string.gallery_time_future)
        diff < 60_000 -> galleryString(Res.string.gallery_time_now)
        diff < 120_000 -> galleryPlural(Res.plurals.gallery_time_minutes, 1, 1)
        diff < 3_000_000 -> (diff / 60_000).toInt().let { galleryPlural(Res.plurals.gallery_time_minutes, it, it) }
        diff < 5_400_000 -> galleryPlural(Res.plurals.gallery_time_hours, 1, 1)
        diff < 86_400_000 -> (diff / 3_600_000).toInt().let { galleryPlural(Res.plurals.gallery_time_hours, it, it) }
        diff < 172_800_000 -> galleryString(Res.string.gallery_time_yesterday)
        diff < 604_800_000 -> galleryString(Res.string.gallery_time_days, (diff / 86_400_000).toInt())
        else -> {
            val zone = TimeZone.currentSystemDefault()
            val date = Instant.fromEpochMilliseconds(time).toLocalDateTime(zone).date
            val year = date.year.takeIf { it != now.toLocalDateTime(zone).year }
            val zh = (LocalGalleryLanguage.current ?: LocalLocale.current.language).startsWith("zh")
            if (zh) {
                (year?.let { "${it}年" }.orEmpty()) + "${date.month.ordinal + 1}月${date.day}日"
            } else {
                listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")[date.month.ordinal] + " ${date.day}" + year?.let { ", $it" }.orEmpty()
            }
        }
    }
}
