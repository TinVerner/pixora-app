package com.pixora.gallery.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import coil3.compose.AsyncImage
import com.pixora.gallery.R
import com.pixora.gallery.background.JobState
import com.pixora.gallery.classification.ContentCategory
import com.pixora.gallery.classification.ExplainCode
import com.pixora.gallery.cleanup.CleanupReason

@Composable
fun categoryLabel(category: ContentCategory): String = stringResource(
    when (category) {
        ContentCategory.MOVIE -> R.string.cat_movie
        ContentCategory.SERIES -> R.string.cat_series
        ContentCategory.DORAMA -> R.string.cat_dorama
        ContentCategory.ANIME -> R.string.cat_anime
        ContentCategory.RECIPE -> R.string.cat_recipe
        ContentCategory.NOTE -> R.string.cat_note
        ContentCategory.SHOPPING -> R.string.cat_shopping
        ContentCategory.LINK -> R.string.cat_link
        ContentCategory.ADDRESS -> R.string.cat_address
        ContentCategory.EVENT -> R.string.cat_event
        ContentCategory.DOCUMENT -> R.string.cat_document
        ContentCategory.OTHER -> R.string.cat_other
    },
)

@Composable
fun explainLabel(code: ExplainCode): String = stringResource(
    when (code) {
        ExplainCode.EMPTY -> R.string.explain_empty
        ExplainCode.LOW_SIGNAL -> R.string.explain_low
        ExplainCode.KEYWORDS -> R.string.explain_keywords
        ExplainCode.TITLE_FROM_YEAR -> R.string.explain_year
        ExplainCode.AMBIGUOUS -> R.string.explain_ambiguous
        ExplainCode.NO_TITLE -> R.string.explain_none
        ExplainCode.LINK -> R.string.explain_link
        ExplainCode.RECIPE -> R.string.explain_recipe
        ExplainCode.LIST -> R.string.explain_list
        ExplainCode.ADDRESS -> R.string.explain_address
        ExplainCode.EVENT -> R.string.explain_event
        ExplainCode.DOCUMENT -> R.string.explain_document
    },
)

@Composable
fun reasonLabel(reason: CleanupReason, count: Int, keep: Int): String = when (reason) {
    CleanupReason.EXACT_DUPLICATE -> stringResource(R.string.exact_body, count)
    CleanupReason.SIMILAR_GROUP -> stringResource(R.string.similar_body, count, keep)
    CleanupReason.BLURRY -> stringResource(R.string.blur_body, count)
    CleanupReason.OLD_SCREENSHOT -> stringResource(R.string.old_body, count)
    CleanupReason.ALREADY_SAVED -> stringResource(R.string.saved_body, count)
}

@Composable
fun Thumb(uri: String, size: Dp, modifier: Modifier = Modifier) {
    AsyncImage(
        model = Uri.parse(uri),
        contentDescription = stringResource(R.string.open_photo),
        modifier = modifier.size(size).clip(RoundedCornerShape(14.dp)),
        contentScale = ContentScale.Crop,
    )
}

@Composable
fun JobBanner(state: JobState, modifier: Modifier = Modifier) {
    if (!state.running && state.message == null) return
    val label = when (state.name) {
        "index" -> stringResource(R.string.job_index)
        "ocr" -> stringResource(R.string.job_ocr)
        "dup" -> stringResource(R.string.job_dup)
        "similar" -> stringResource(R.string.job_similar)
        "labels" -> stringResource(R.string.job_labels)
        "match" -> stringResource(R.string.find_matches)
        else -> state.name
    }
    Box(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (state.running) {
            val fraction = if (state.total > 0) state.done / state.total.toFloat() else 0f
            LinearProgressIndicator(
                progress = { fraction.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(R.string.progress, label, state.done, state.total),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
        } else if (state.message != null) {
            Text(state.message, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
fun SkeletonCard(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "alpha",
    )
    Box(
        modifier
            .fillMaxWidth()
            .height(72.dp)
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha)),
    )
}

@Composable
fun EmptyText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(24.dp),
    )
}
