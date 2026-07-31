package io.legado.app.ui.bookshelf

import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

class CoverDecodeSizeTest {

    @Test
    fun roundsDisplaySizeUpToStableGrid() {
        assertEquals(IntSize.Zero, coverDecodeSize(IntSize.Zero))
        assertEquals(IntSize(64, 64), coverDecodeSize(IntSize(1, 63)))
        assertEquals(IntSize(128, 192), coverDecodeSize(IntSize(65, 129)))
    }

    @Test
    fun firstValidSizeIgnoresLaterWindowResize() = runBlocking {
        val sizes = MutableStateFlow(IntSize.Zero)
        var result: IntSize? = null
        val job = launch { result = firstValidCoverDecodeSize(sizes) }

        yield()
        sizes.value = IntSize(65, 129)
        job.join()
        sizes.value = IntSize(400, 600)

        assertEquals(IntSize(128, 192), result)
    }
}
