package com.lagradost.cloudstream.mac

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

private val imdbYellow = Color(0xFFF5C518)

/**
 * "Ratings & Reviews": IMDb's score with how many people gave each score from 10 down to 1,
 * and a shelf of the most helpful reviews. "See all" opens every review, sortable.
 */
@Composable
fun RatingsAndReviews(imdb: ImdbInfo, featured: ImdbReviewPage?, onOpenAll: () -> Unit) {
    if (imdb.rating == null && featured?.reviews.isNullOrEmpty()) return
    Column(Modifier.fillMaxWidth().padding(top = 28.dp)) {
        Row(Modifier.padding(start = 48.dp, end = 32.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Ratings & Reviews", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
            val total = featured?.total ?: imdb.reviewCount
            if ((total ?: 0) > 0) {
                SeeAllCapsule("See all ${"%,d".format(total)} reviews", onOpenAll)
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            if (imdb.rating != null) {
                RatingBreakdown(imdb, Modifier.padding(start = 48.dp).width(340.dp))
                Spacer(Modifier.width(28.dp))
            }
            val reviews = featured?.reviews.orEmpty()
            if (reviews.isNotEmpty()) {
                LazyRow(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(end = 32.dp, start = if (imdb.rating == null) 48.dp else 0.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(reviews, key = { it.id }) { review ->
                        ReviewCard(review, Modifier.width(340.dp).height(236.dp), maxLines = 5, onClick = onOpenAll)
                    }
                }
            }
        }
    }
}

@Composable
private fun SeeAllCapsule(text: String, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.09f))
            .handCursor().clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    )
}

/** The score, the vote count, and one bar per score showing its share of all votes. */
@Composable
fun RatingBreakdown(imdb: ImdbInfo, modifier: Modifier = Modifier) {
    val histogram = imdb.ratingHistogram
    val total = histogram.sum().takeIf { it > 0 } ?: imdb.votes ?: 0
    val max = histogram.maxOrNull()?.takeIf { it > 0 } ?: 1
    Column(modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("%.1f".format(imdb.rating ?: 0.0), style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.padding(bottom = 8.dp)) {
                Text("out of 10", style = MaterialTheme.typography.labelMedium, color = secondaryText)
                Text("${compactCount(total)} ratings", style = MaterialTheme.typography.labelSmall, color = secondaryText)
            }
            Spacer(Modifier.weight(1f))
            Text(
                "IMDb", color = Color.Black, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.padding(bottom = 12.dp).background(imdbYellow, RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
        if (histogram.size == 10) {
            Spacer(Modifier.height(10.dp))
            for (score in 10 downTo 1) {
                val votes = histogram[score - 1]
                Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("$score", style = MaterialTheme.typography.labelSmall, color = secondaryText, modifier = Modifier.width(20.dp))
                    Box(Modifier.weight(1f).height(7.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.1f))) {
                        Box(
                            Modifier.fillMaxHeight().fillMaxWidth((votes.toFloat() / max).coerceIn(0.005f, 1f))
                                .clip(RoundedCornerShape(50)).background(imdbYellow.copy(alpha = 0.9f)),
                        )
                    }
                    Text(
                        if (total > 0) "%.0f%%".format(votes * 100.0 / total) else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = secondaryText,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(42.dp),
                    )
                    Text(
                        compactCount(votes),
                        style = MaterialTheme.typography.labelSmall,
                        color = secondaryText.copy(alpha = 0.7f),
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(52.dp),
                    )
                }
            }
        }
    }
}

/** A review: the reviewer's score, the headline, the text (hidden behind a tap for spoilers), who and when. */
@Composable
fun ReviewCard(review: ImdbReview, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE, onClick: (() -> Unit)? = null) {
    var revealed by remember(review.id) { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.06f),
        modifier = modifier,
    ) {
        Column(
            Modifier
                .then(if (onClick != null) Modifier.handCursor().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick) else Modifier)
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (review.rating != null) {
                    Icon(Icons.Default.Star, null, tint = imdbYellow, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("${review.rating}/10", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
                }
                Spacer(Modifier.weight(1f))
                review.date?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = secondaryText) }
            }
            review.title?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(6.dp))
            if (review.spoiler && !revealed) {
                Text(
                    "Contains spoilers. Show review",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.handCursor().clickable { revealed = true },
                )
            } else {
                Text(
                    review.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                    maxLines = maxLines,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (maxLines == Int.MAX_VALUE) Modifier else Modifier.weight(1f, fill = false),
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(review.author, style = MaterialTheme.typography.labelSmall, color = secondaryText, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (review.upVotes > 0) {
                    Icon(Icons.Outlined.ThumbUp, "Found helpful", tint = secondaryText, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(compactCount(review.upVotes.toLong()), style = MaterialTheme.typography.labelSmall, color = secondaryText)
                }
            }
        }
    }
}

/** Every IMDb review of a title, sortable, loading more as you scroll. */
@Composable
fun ReviewsDialog(imdb: ImdbInfo, onDismiss: () -> Unit) {
    var sort by rememberSaveable { mutableStateOf(ReviewSort.Helpful) }
    val reviews = remember { mutableStateListOf<ImdbReview>() }
    var total by remember { mutableStateOf(imdb.reviewCount ?: 0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf { (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= listState.layoutInfo.totalItemsCount - 3 }
    }

    // One loader per sort: fetch a page, wait until the list is scrolled near its end, fetch the next
    LaunchedEffect(sort) {
        reviews.clear(); error = null; loading = true
        listState.scrollToItem(0)
        var cursor: String? = null
        while (true) {
            val page = try {
                Imdb.reviews(imdb.id, sort, cursor)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (page == null) {
                error = "Couldn't load reviews from IMDb."
                break
            }
            reviews += page.reviews.filter { r -> reviews.none { it.id == r.id } }
            total = page.total
            cursor = page.nextCursor ?: break
            loading = false
            snapshotFlow { nearEnd }.first { it }
            loading = true
        }
        loading = false
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.width(860.dp).fillMaxHeight(0.88f),
        ) {
            Column {
                Row(Modifier.padding(start = 28.dp, end = 16.dp, top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Reviews", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
                        Text("${imdb.title} · ${"%,d".format(total)} IMDb user reviews", style = MaterialTheme.typography.labelMedium, color = secondaryText)
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.handCursor()) { Icon(Icons.Default.Close, "Close", tint = MaterialTheme.colorScheme.onBackground) }
                }
                LazyRow(contentPadding = PaddingValues(horizontal = 28.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ReviewSort.entries) { option ->
                        FilterChip(selected = sort == option, onClick = { sort = option }, label = { Text(option.label) }, modifier = Modifier.handCursor())
                    }
                }
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(start = 28.dp, end = 28.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (imdb.rating != null) {
                        item(key = "breakdown") { RatingBreakdown(imdb, Modifier.width(360.dp).padding(bottom = 8.dp)) }
                    }
                    items(reviews, key = { it.id }) { review -> ReviewCard(review, Modifier.fillMaxWidth()) }
                    when {
                        error != null -> item { Text(error!!, color = MaterialTheme.colorScheme.error) }
                        loading -> item {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            }
                        }
                        reviews.isEmpty() -> item { Text("No reviews yet.", color = secondaryText) }
                    }
                }
            }
        }
    }
}
