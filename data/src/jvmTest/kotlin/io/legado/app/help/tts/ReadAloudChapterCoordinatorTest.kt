package io.legado.app.help.tts

import io.legado.app.model.PositionOrigin
import io.legado.app.service.ReadAloudChapterDataPort
import io.legado.app.service.ReadAloudChapterNavigationPort
import io.legado.app.service.ReadAloudChapterPlan
import io.legado.app.service.ReadAloudControllerShared
import io.legado.app.service.ReadAloudPlanBuilder
import io.legado.app.service.ReadAloudPlaybackPort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阅读位置跟随与等章答复的判定金样。
 *
 * 谁写的位置决定要不要跟着走: 朗读自己推进不该触发跟随, 用户翻页/翻章必须跟。等章时
 * 请求装载、答复到手才续读、没有阅读页立即上报 —— 这些都不该靠真机听感判断。
 */
class ReadAloudChapterCoordinatorTest {

    private class FakePorts(
        val source: FakeChapterData,
    ) : ReadAloudHostPorts {

        override val chapterData = object : ReadAloudChapterDataHostPort {
            override val chapterCount: Int get() = source.chapterCount
            override fun chapterText(chapterIndex: Int): String? = source.textOf(chapterIndex)
        }
        val signals = MutableSharedFlow<ReadAloudChapterSignal>(extraBufferCapacity = 8)
        val prefetched = mutableListOf<Int>()
        var sourceAvailable = true
        var position: ReadAloudPosition? = null
        var readyChapters: MutableSet<Int> = mutableSetOf()

        override val chapterNavigation = ReadAloudChapterNavigationPort { }
        override val positionPublisher = object : ReadAloudPositionPublisher {
            override fun publishPosition(chapterPosition: Int) = Unit
            override fun publishState(state: Int) = Unit
            override fun publishTimer(minute: Int) = Unit
        }
        override val mediaControl = object : ReadAloudMediaControlPort {
            override fun sync(isPlaying: Boolean) = Unit
            override fun release() = Unit
        }
        override val currentPosition: ReadAloudPosition? get() = position
        override val positionUpdates: Flow<ReadAloudPosition> get() = emptyFlow()
        override val chapterUpdates: Flow<ReadAloudChapterUpdate> get() = emptyFlow()
        override val chapterSourceAvailable: Boolean get() = sourceAvailable
        override val chapterSignals: Flow<ReadAloudChapterSignal> get() = signals
        override fun prefetchChapter(chapterIndex: Int) {
            prefetched += chapterIndex
        }
        override fun isChapterTextReady(chapterIndex: Int): Boolean =
            chapterIndex in readyChapters
    }

    private class FakeChapterData(
        override val chapterCount: Int,
        texts: Map<Int, String>,
    ) : ReadAloudChapterDataPort {

        val texts: MutableMap<Int, String> = texts.toMutableMap()
        private val builder = ReadAloudPlanBuilder()
        override fun loadChapterPlan(
            chapterIndex: Int,
            chapterPosition: Int,
            fromLastSpeakable: Boolean,
        ): ReadAloudChapterPlan? {
            val text = texts[chapterIndex] ?: return null
            return builder.buildPlan(text, chapterPosition, fromLastSpeakable)
        }
        override fun isChapterPaged(chapterIndex: Int) = chapterIndex in texts.keys

        fun textOf(chapterIndex: Int): String? = texts[chapterIndex]
    }

    private class FakePlayback : ReadAloudPlaybackPort {
        override fun onPlay(text: String, paragraphIndex: Int, playbackToken: Long) = Unit
        override fun onStop() = Unit
        override fun onPause() = Unit
        override fun onResume() = true
    }

    private fun controller(ports: FakePorts) = ReadAloudControllerShared(
        chapterData = ports.source,
        chapterNavigation = ports.chapterNavigation,
        playbackPort = FakePlayback(),
    )

    @Test
    fun `朗读自己写入的位置不触发跟随`() {
        val ports = FakePorts(FakeChapterData(3, mapOf(0 to "甲一", 1 to "乙一")))
        val controller = controller(ports)
        controller.start(0)
        val coordinator = ReadAloudChapterCoordinator(ports) { controller }

        var followed = 0
        coordinator.onPosition(
            position = ReadAloudPosition(0, 5, PositionOrigin.ReadAloud),
            paragraphRange = { IntRange.EMPTY },
            onFollow = { followed++ },
            onPauseMove = { },
        )

        assertEquals("朗读自己推进的位置不该被当成用户翻页", 0, followed)
    }

    @Test
    fun `用户翻到别的章时跟随过去`() {
        val ports = FakePorts(FakeChapterData(3, mapOf(0 to "甲一", 1 to "乙一")))
        val controller = controller(ports)
        controller.start(0)
        val coordinator = ReadAloudChapterCoordinator(ports) { controller }

        var followedTo: ReadAloudPosition? = null
        coordinator.onPosition(
            position = ReadAloudPosition(2, 10, PositionOrigin.User),
            paragraphRange = { IntRange.EMPTY },
            onFollow = { followedTo = it },
            onPauseMove = { },
        )

        assertEquals(2, followedTo?.chapterIndex)
    }

    @Test
    fun `用户在同一段内挪动位置不重开`() {
        val ports = FakePorts(FakeChapterData(3, mapOf(0 to "甲一", 1 to "乙一")))
        val controller = controller(ports)
        controller.start(0)
        val coordinator = ReadAloudChapterCoordinator(ports) { controller }

        var followed = 0
        coordinator.onPosition(
            position = ReadAloudPosition(0, 3, PositionOrigin.User),
            paragraphRange = { 2 until 12 },
            onFollow = { followed++ },
            onPauseMove = { },
        )

        assertEquals(0, followed)
    }

    @Test
    fun `暂停态下位置被挪走时交给调用方暂存`() {
        val ports = FakePorts(FakeChapterData(3, mapOf(0 to "甲一", 1 to "乙一")))
        val controller = controller(ports)
        controller.start(0)
        controller.pause()
        val coordinator = ReadAloudChapterCoordinator(ports) { controller }

        var paused: ReadAloudPosition? = null
        coordinator.onPosition(
            position = ReadAloudPosition(1, 7, PositionOrigin.User),
            paragraphRange = { 0 until 5 },
            onFollow = { },
            onPauseMove = { paused = it },
        )

        assertEquals(1, paused?.chapterIndex)
    }

    @Test
    fun `等章时请求装载而不是空等`() {
        val ports = FakePorts(FakeChapterData(3, mapOf(0 to "甲一")))
        val controller = controller(ports)
        controller.start(1)
        assertEquals(ReadAloudControllerShared.ReadAloudState.WAITING, controller.state.value)
        val coordinator = ReadAloudChapterCoordinator(ports) { controller }

        coordinator.onWaiting()

        assertTrue("进入等待应请求装载该章", ports.prefetched.contains(1))
    }

    @Test
    fun `没有阅读页时等章直接上报而不是静默等待`() {
        val ports = FakePorts(FakeChapterData(3, mapOf(0 to "甲一")))
        ports.sourceAvailable = false
        val controller = controller(ports)
        controller.start(1)
        val coordinator = ReadAloudChapterCoordinator(ports) { controller }

        coordinator.onWaiting()

        assertEquals(
            ReadAloudControllerShared.ReadAloudState.ERROR,
            controller.state.value,
        )
    }

    @Test
    fun `装载答复到达且正文就绪时续读`() {
        val source = FakeChapterData(3, mapOf(0 to "甲一"))
        val ports = FakePorts(source)
        val controller = controller(ports)
        controller.start(1)
        assertEquals(ReadAloudControllerShared.ReadAloudState.WAITING, controller.state.value)
        val coordinator = ReadAloudChapterCoordinator(ports) { controller }
        source.texts[1] = "乙一"
        ports.readyChapters += 1

        coordinator.onWaiting()

        assertEquals(
            ReadAloudControllerShared.ReadAloudState.PLAYING,
            controller.state.value,
        )
    }

    @Test
    fun `等章经装载答复信号走通续读链路`() = runBlocking {
        val source = FakeChapterData(3, mapOf(0 to "甲一"))
        val ports = FakePorts(source)
        val controller = controller(ports)
        controller.start(1)
        assertEquals(ReadAloudControllerShared.ReadAloudState.WAITING, controller.state.value)
        val coordinator = ReadAloudChapterCoordinator(ports) { controller }
        val watch = launch { coordinator.startWatch() }
        try {
            // 先让监听真正挂上订阅
            repeat(4) { yield() }
            source.texts[1] = "乙一"
            assertTrue(ports.signals.tryEmit(ReadAloudChapterSignal.Settled(1)))
            repeat(16) { yield() }
            assertEquals(
                "装载答复到达且正文就绪时应续读",
                ReadAloudControllerShared.ReadAloudState.PLAYING,
                controller.state.value,
            )
        } finally {
            watch.cancel()
        }
    }

    @Test
    fun `装载答复属于别的章时被忽略`() = runBlocking {
        val ports = FakePorts(FakeChapterData(3, mapOf(0 to "甲一")))
        val controller = controller(ports)
        controller.start(1)
        val coordinator = ReadAloudChapterCoordinator(ports) { controller }
        val watch = launch { coordinator.startWatch() }
        try {
            repeat(4) { yield() }
            assertTrue(ports.signals.tryEmit(ReadAloudChapterSignal.Settled(0)))
            repeat(16) { yield() }
            assertEquals(
                "异章答复不得驱动续读或误报",
                ReadAloudControllerShared.ReadAloudState.WAITING,
                controller.state.value,
            )
            assertEquals(1, controller.chapterIndex.value)
        } finally {
            watch.cancel()
        }
    }

    @Test
    fun `用户已翻到别的章时收到答复不续读也不误报`() = runBlocking {
        val source = FakeChapterData(3, mapOf(0 to "甲一"))
        val ports = FakePorts(source)
        val controller = controller(ports)
        controller.start(1)
        // 阅读页已被用户翻到第 3 章: 续读会把阅读页拽回原章
        ports.position = ReadAloudPosition(2, 0, PositionOrigin.User)
        val coordinator = ReadAloudChapterCoordinator(ports) { controller }
        val watch = launch { coordinator.startWatch() }
        try {
            repeat(4) { yield() }
            source.texts[1] = "乙一"
            assertTrue(ports.signals.tryEmit(ReadAloudChapterSignal.Settled(1)))
            repeat(16) { yield() }
            assertEquals(
                "用户已翻走时不续读也不判读不了",
                ReadAloudControllerShared.ReadAloudState.WAITING,
                controller.state.value,
            )
            assertEquals(1, controller.chapterIndex.value)
        } finally {
            watch.cancel()
        }
    }
}
