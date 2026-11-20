package com.example.music

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.music.domain.art.TrackArtRepository
import com.example.music.ui.components.TrackArtworkContent
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArtworkUiRegressionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun nextPreviousAndDelayedArtworkNeverLeaveThePreviousCoverOnScreen() {
        fun ready(color: Int) = TrackArtRepository.ArtLoadState.Ready(
            Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(color) },
        )
        val red = MutableStateFlow<TrackArtRepository.ArtLoadState>(ready(Color.RED))
        val blue = MutableStateFlow<TrackArtRepository.ArtLoadState>(TrackArtRepository.ArtLoadState.Loading)
        val selected = mutableStateOf(red)
        compose.setContent {
            TrackArtworkContent(selected.value, Modifier.size(64.dp).testTag("cover"), 0.dp, "♪", {})
        }
        fun redPixel(): Float = compose.onNodeWithTag("cover").captureToImage().toPixelMap()[5, 5].red
        fun bluePixel(): Float = compose.onNodeWithTag("cover").captureToImage().toPixelMap()[5, 5].blue
        compose.waitForIdle()
        assertTrue(redPixel() > .9f && bluePixel() < .1f)
        compose.runOnIdle { selected.value = blue }
        compose.waitForIdle()
        assertTrue("Loading a different track must clear the red cover", redPixel() < .9f || bluePixel() > .1f)
        compose.runOnIdle { blue.value = ready(Color.BLUE) }
        compose.waitForIdle()
        assertTrue(bluePixel() > .9f && redPixel() < .1f)
        repeat(12) {
            compose.runOnIdle { selected.value = red }
            compose.waitForIdle()
            assertTrue(redPixel() > .9f && bluePixel() < .1f)
            compose.runOnIdle { selected.value = blue }
            compose.waitForIdle()
            assertTrue(bluePixel() > .9f && redPixel() < .1f)
        }
    }
}
