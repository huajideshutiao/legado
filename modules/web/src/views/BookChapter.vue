<template>
  <div
    class="chapter-wrapper"
    :style="bodyTheme"
    :class="{ night: isNight, day: !isNight }"
    @click="handleWrapperClick"
  >
    <div class="tool-bar" :style="leftBarTheme" @click.stop>
      <div class="tools">
        <div class="tool-icon" title="返回上一页" @click.stop="goBack">
          <div class="iconfont">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" width="18" height="18" style="vertical-align: middle;">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10 19l-7-7m0 0l7-7m-7 7h18" />
            </svg>
          </div>
          <div class="icon-text">返回</div>
        </div>
        <div class="tool-icon" @click.stop="popCataVisible = !popCataVisible">
          <div class="iconfont">&#58905;</div>
          <div class="icon-text">目录</div>
        </div>
        <div class="tool-icon" @click.stop="readSettingsVisible = !readSettingsVisible">
          <div class="iconfont">&#58971;</div>
          <div class="icon-text">设置</div>
        </div>
        <div class="tool-icon" title="刷新正文" @click.stop="reParseCurrentChapter">
          <div class="iconfont">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" width="16" height="16" style="vertical-align: middle;">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
            </svg>
          </div>
          <div class="icon-text">刷新</div>
        </div>
        <div class="tool-icon" :class="{ 'no-point': false }" @click="toTop">
          <div class="iconfont">&#58914;</div>
          <div class="icon-text">顶部</div>
        </div>
        <div class="tool-icon" :class="{ 'no-point': false }" @click="toBottom">
          <div class="iconfont">&#58915;</div>
          <div class="icon-text">底部</div>
        </div>
      </div>
    </div>
    <div class="read-bar" :style="rightBarTheme" @click.stop>
      <div class="tools">
        <div class="tool-icon" title="上一章" :class="{ 'no-point': noPoint }" @click.stop="toPreChapter">
          <div class="iconfont">&#58920;</div>
          <span v-if="miniInterface">上一章</span>
        </div>
        <div class="tool-icon" title="下一章" :class="{ 'no-point': noPoint }" @click.stop="toNextChapter">
          <span v-if="miniInterface">下一章</span>
          <div class="iconfont">&#58913;</div>
        </div>
      </div>
    </div>

    <BookCatalogDialog
      v-model:visible="popCataVisible"
      :catalog="catalog"
      :currentChapterIndex="chapterIndex"
      :isNight="isNight"
      @select="onSelectCatalogChapter"
    />

    <div v-if="readSettingsVisible" class="web-dialog-overlay" @click.self="readSettingsVisible = false">
      <div class="web-dialog popup" :style="{ background: popupColor, maxWidth: popupWidth + 'px' }">
        <read-settings />
      </div>
    </div>

    <div class="chapter" ref="content" :style="chapterTheme" :class="effectiveMode + '-mode'">
      <div class="content">
        <div class="top-bar" ref="top" :class="{ compact: effectiveMode !== 'text' }"></div>

        <!-- 漫画模式: 垂直瀑布流居中平铺 -->
        <comic-reader
          v-if="effectiveMode === 'comic'"
          :chapterIndex="chapterIndex"
          :title="currentChapterTitle"
          :contents="currentChapterRawContent"
          :bookUrl="store.readingBook.bookUrl"
          :origin="store.readingBook.origin"
          :hasImageDecode="hasImageDecode"
          :readWidth="store.config.readWidth"
          @toggleToolbar="showToolBar = !showToolBar"
          @reParse="reParseCurrentChapter"
          @prevChapter="toPreChapter"
          @nextChapter="toNextChapter"
        />

        <!-- 音频模式: 极简有声播放器 -->
        <audio-reader
          v-else-if="effectiveMode === 'audio'"
          :chapterIndex="chapterIndex"
          :title="currentChapterTitle"
          :contents="currentChapterRawContent"
          :bookName="store.readingBook.name"
          :author="store.readingBook.author"
          :coverUrl="store.readingBook.coverUrl"
          :origin="store.readingBook.origin"
          :catalog="catalog"
          :initialPos="chapterPos"
          @toggleToolbar="showToolBar = !showToolBar"
          @prevChapter="toPreChapter"
          @nextChapter="toNextChapter"
          @saveProgress="onAudioProgress"
          @reParse="reParseCurrentChapter"
        />

        <!-- 视频模式: 简单原生视频播放器 -->
        <video-reader
          v-else-if="effectiveMode === 'video'"
          :chapterIndex="chapterIndex"
          :title="currentChapterTitle"
          :contents="currentChapterRawContent"
          :bookName="store.readingBook.name"
          :origin="store.readingBook.origin"
          :catalog="catalog"
          @toggleToolbar="showToolBar = !showToolBar"
          @prevChapter="toPreChapter"
          @nextChapter="toNextChapter"
          @reParse="reParseCurrentChapter"
        />

        <!-- 文本模式 (默认) -->
        <template v-else>
          <div
            v-for="data in chapterData"
            :key="data.index"
            ref="chapter"
          >
            <chapter-content
              ref="chapterRef"
              :chapterIndex="data.index"
              :contents="data.content"
              :title="data.title"
              :spacing="store.config.spacing"
              :fontSize="fontSize"
              :fontFamily="fontFamily"
              :hasImageDecode="hasImageDecode"
              @readedLengthChange="onReadedLengthChange"
              v-if="showContent"
            />
          </div>
          <div class="loading" ref="loading"></div>
        </template>

        <div class="bottom-bar" ref="bottom" :class="{ compact: effectiveMode !== 'text' }"></div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import jump from '@/plugins/jump'
import settings from '@/config/themeConfig'
import API from '@api'
import { useLoading } from '@/hooks/loading'
import { useThrottleFn } from '@vueuse/shared'
import { isNullOrBlank } from '@/utils/utils'
import { toast } from '@/utils/toast'
import { msgbox } from '@/utils/toast'
import ComicReader from '@/components/ComicReader.vue'
import AudioReader from '@/components/AudioReader.vue'
import VideoReader from '@/components/VideoReader.vue'
import ChapterContent from '@/components/ChapterContent.vue'
import BookCatalogDialog from '@/components/BookCatalogDialog.vue'

const content = ref()
const { isLoading, loadingWrapper } = useLoading(content, '正在获取信息')
const store = useBookStore()

const {
  catalog,
  miniInterface,
  showContent,
  bookProgress,
  theme,
  isNight,
} = storeToRefs(store)

const popCataVisible = ref(false)
const readSettingsVisible = ref(false)

const onSelectCatalogChapter = (index: number) => {
  popCataVisible.value = false
  chapterIndex.value = index
  getContent(index)
  store.saveBookProgress()
}

const bookType = ref<number>(0)
const bookSourceType = ref<number | undefined>(undefined)
const currentBookSource = ref<any>(null)
const hasImageDecode = computed(() => {
  const decode = currentBookSource.value?.contentRule?.imageDecode
  return Boolean(decode && typeof decode === 'string' && decode.trim().length > 0)
})

const chapterPos = computed({
  get: () => store.readingBook.chapterPos,
  set: value => (store.readingBook.chapterPos = value),
})
const chapterIndex = computed({
  get: () => store.readingBook.chapterIndex,
  set: value => (store.readingBook.chapterIndex = value),
})
const isSeachBook = computed({
  get: () => store.readingBook.isSeachBook,
  set: value => (store.readingBook.isSeachBook = value),
})

watch(
  () => store.readingBook,
  book => {
    localStorage.setItem('readingRecent', JSON.stringify(book))
    sessionStorage.setItem('chapterIndex', book.chapterIndex.toString())
    sessionStorage.setItem('chapterPos', book.chapterPos.toString())
  },
  { deep: 1 },
)

const chapterData = ref<{ index: number; content: string[]; title: string; rawContent?: string }[]>([])

const currentChapterItem = computed(() => {
  return chapterData.value.find(c => c.index === chapterIndex.value) || chapterData.value[0]
})
const currentChapterTitle = computed(() => {
  return currentChapterItem.value?.title || catalog.value[chapterIndex.value]?.title || ''
})
const currentChapterRawContent = computed(() => {
  return currentChapterItem.value?.rawContent || currentChapterItem.value?.content || []
})

const effectiveMode = computed<'text' | 'comic' | 'audio' | 'video'>(() => {
  // 1. 严格依据书籍类型标志位 (BookType 掩码，对齐 Legado 原版: 2048 视频, 32 音频, 64 图片/漫画, 8 文本)
  const bType = bookType.value || store.readingBook.type || 0
  if (bType > 0) {
    if ((bType & 2048) !== 0) return 'video'
    if ((bType & 32) !== 0) return 'audio'
    if ((bType & 64) !== 0) return 'comic'
    if ((bType & 8) !== 0) return 'text'
  }

  // 2. 兜底依据书源声明类型 (BookSourceType: 0: 文本, 1: 音频, 2: 漫画, 4: 视频)
  if (typeof bookSourceType.value === 'number') {
    if (bookSourceType.value === 1) return 'audio'
    if (bookSourceType.value === 2) return 'comic'
    if (bookSourceType.value === 4) return 'video'
    if (bookSourceType.value === 0) return 'text'
  }

  // 默认文本模式 (小说)
  return 'text'
})

const infiniteLoading = computed(() => store.config.infiniteLoading)
let scrollObserver: IntersectionObserver | null
const loading = ref()
watchEffect(() => {
  if (effectiveMode.value !== 'text' || !infiniteLoading.value) {
    scrollObserver?.disconnect()
  } else {
    if (loading.value) scrollObserver?.observe(loading.value)
  }
})
const loadMore = () => {
  const index = chapterData.value.slice(-1)[0].index
  if (catalog.value.length - 1 > index) {
    getContent(index + 1, false)
    store.saveBookProgress()
  }
}
const onReachBottom = (entries: IntersectionObserverEntry[]) => {
  if (isLoading.value) return
  for (const { isIntersecting } of entries) {
    if (!isIntersecting) return
    loadMore()
  }
}

const fontFamily = computed(() => {
  if (store.config.font >= 0) {
    return settings.fonts[store.config.font]
  }
  return store.config.customFontName
})
const fontSize = computed(() => {
  return store.config.fontSize + 'px'
})

const bodyColor = computed(() => settings.themes[theme.value].body)
const chapterColor = computed(() => settings.themes[theme.value].content)
const popupColor = computed(() => settings.themes[theme.value].popup)

const readWidth = computed(() => {
  if (!miniInterface.value) {
    return store.config.readWidth - 130 + 'px'
  } else {
    return window.innerWidth + 'px'
  }
})
const popupWidth = computed(() => {
  if (!miniInterface.value) {
    return store.config.readWidth - 33
  } else {
    return window.innerWidth - 33
  }
})
const bodyTheme = computed(() => {
  return { background: bodyColor.value }
})
const chapterTheme = computed(() => {
  if (effectiveMode.value === 'comic') {
    return {
      background: chapterColor.value,
      width: '100%',
      maxWidth: `${store.config.readWidth}px`,
    }
  }
  if (effectiveMode.value !== 'text') {
    return {
      background: chapterColor.value,
      width: '100%',
      maxWidth: '100%',
    }
  }
  return { background: chapterColor.value, width: readWidth.value }
})
const showToolBar = ref(false)

const scrollPageUp = () => {
  const currentScroll = document.documentElement.scrollTop || window.scrollY
  if (currentScroll <= 10) {
    toPreChapter()
  } else {
    jump(0 - document.documentElement.clientHeight + 100, {
      duration: store.config.jumpDuration || 300,
    })
  }
}

const scrollPageDown = () => {
  const currentScroll = document.documentElement.scrollTop || window.scrollY
  const maxScroll = document.documentElement.scrollHeight - document.documentElement.clientHeight
  if (currentScroll >= maxScroll - 10) {
    toNextChapter()
  } else {
    jump(document.documentElement.clientHeight - 100, {
      duration: store.config.jumpDuration || 300,
    })
  }
}

const handleWrapperClick = (e: MouseEvent) => {
  const target = e.target as HTMLElement
  if (
    target.closest(
      '.tool-bar, .read-bar, .web-dialog, .web-dialog-overlay, .comic-reader, .audio-reader, .video-reader'
    )
  ) {
    return
  }

  // 漫画模式已由 comic-reader 内部自行处理左中右点击
  if (effectiveMode.value === 'comic') {
    return
  }

  // 音频和视频模式点击外层空白区域直接切换工具栏，不触发翻页滚动
  if (effectiveMode.value === 'audio' || effectiveMode.value === 'video') {
    showToolBar.value = !showToolBar.value
    return
  }

  const clickX = e.clientX
  const clickY = e.clientY
  const windowWidth = window.innerWidth
  const leftThreshold = windowWidth * 0.33
  const rightThreshold = windowWidth * 0.67

  // 1. 若在宽屏下点击视口绝对左上角 (用户习惯寻找返回按钮的区域)，直接触发返回
  if (clickY <= 64 && clickX <= 140) {
    goBack()
    return
  }

  // 2. 屏幕顶部 48px 内属于操作栏安全区，不误触翻页，仅切换工具栏显隐
  if (clickY <= 48) {
    showToolBar.value = !showToolBar.value
    return
  }

  if (clickX < leftThreshold) {
    // 点击左侧：上一页 / 向上翻页
    scrollPageUp()
  } else if (clickX > rightThreshold) {
    // 点击右侧：下一页 / 向下翻页
    scrollPageDown()
  } else {
    // 点击中间：呼出 / 隐藏工具栏
    showToolBar.value = !showToolBar.value
  }
}
const leftBarTheme = computed(() => {
  return {
    background: popupColor.value,
    marginLeft: miniInterface.value
      ? 0
      : -(store.config.readWidth / 2 + 68) + 'px',
    display: miniInterface.value && !showToolBar.value ? 'none' : 'block',
  }
})
const rightBarTheme = computed(() => {
  return {
    background: popupColor.value,
    marginRight: miniInterface.value
      ? 0
      : -(store.config.readWidth / 2 + 52) + 'px',
    display: miniInterface.value && !showToolBar.value ? 'none' : 'block',
  }
})

const onResize = () => {
  store.setMiniInterface(window.innerWidth < 776)
  const width = store.config.readWidth
  checkPageWidth(width)
}
const checkPageWidth = (readWidth: number) => {
  if (store.miniInterface) return
  if (readWidth < 640) store.config.readWidth = 640
  if (readWidth + 2 * 68 > window.innerWidth) store.config.readWidth -= 160
}
watch(
  () => store.config.readWidth,
  width => checkPageWidth(width),
)
const top = ref()
const bottom = ref()
const toTop = () => jump(top.value)
const toBottom = () => jump(bottom.value)

const router = useRouter()
const toShelf = () => router.push('/shelf')
const goBack = () => {
  if (window.history.state?.back) {
    router.back()
  } else {
    router.push('/shelf')
  }
}

let contentGeneration = 0

const noPoint = ref(true)
const getContent = (index: number, reloadChapter = true, chapterPos = 0, refresh = false) => {
  if (catalog.value.length === 0) {
    console.warn('[BookChapter] 目录尚未就绪，暂不获取正文')
    return
  }
  const currentGeneration = ++contentGeneration
  if (reloadChapter) {
    store.setShowContent(false)
    jump(top.value, { duration: 0 })
    saveReadingBookProgressToBrowser(index, chapterPos)
    chapterData.value = []
  }
  const bookUrl = store.readingBook.bookUrl
  // 后端 (GSON) 省略零值字段: 首章 index:0 不出现在 JSON → 用目录行号兜底
  const chapterItem = catalog.value[index]
  const title = chapterItem?.title || ''
  const targetChapterIndex = chapterItem?.index ?? index

  loadingWrapper(
    API.getBookContent(bookUrl, store.readingBook.origin, targetChapterIndex, refresh).then(
      res => {
        if (currentGeneration !== contentGeneration || bookUrl !== store.readingBook.bookUrl) {
          return
        }
        if (reloadChapter && index !== chapterIndex.value) {
          return
        }
        if (res.data.isSuccess) {
          const data = res.data.data
          const content = data.split(/\n+/)
          chapterData.value.push({ index, content, title, rawContent: data })
          if (reloadChapter && effectiveMode.value === 'text') toChapterPos(chapterPos)
        } else {
          toast.error(res.data.errorMsg)
          const content = [res.data.errorMsg]
          chapterData.value.push({ index, content, title, rawContent: res.data.errorMsg })
        }
        store.setContentLoading(true)
        noPoint.value = false
        store.setShowContent(true)
        if (!res.data.isSuccess) {
          throw res.data
        }
      },
      err => {
        if (currentGeneration !== contentGeneration || bookUrl !== store.readingBook.bookUrl) {
          return
        }
        if (reloadChapter && index !== chapterIndex.value) {
          return
        }
        const content = ['获取章节内容失败！']
        chapterData.value.push({ index, content, title, rawContent: '获取章节内容失败！' })
        store.setShowContent(true)
        throw err
      },
    ),
  )
}

const reParseCurrentChapter = () => {
  if (catalog.value.length === 0) return
  toast.info('正在刷新当前章节正文...')
  getContent(chapterIndex.value, true, 0, true)
}

const chapter = ref()
const chapterRef = ref()
const toChapterPos = (pos: number) => {
  nextTick(() => {
    if (chapterRef.value && chapterRef.value.length === 1 && chapterRef.value[0]?.scrollToReadedLength)
      chapterRef.value[0].scrollToReadedLength(pos)
  })
}

const onComicPageChange = (_cIndex: number, pageIndex: number) => {
  saveReadingBookProgressToBrowser(chapterIndex.value, pageIndex)
  saveBookProgressThrottle()
}

const onAudioProgress = (posSec: number, targetChapterIndex?: number) => {
  if (typeof targetChapterIndex === 'number' && targetChapterIndex !== chapterIndex.value) {
    return
  }
  saveReadingBookProgressToBrowser(chapterIndex.value, posSec)
  saveBookProgressThrottle()
}

const saveBookProgressThrottle = useThrottleFn(
  () => store.saveBookProgress(),
  60000,
)

const onReadedLengthChange = (index: number, pos: number) => {
  saveReadingBookProgressToBrowser(index, pos)
  saveBookProgressThrottle()
}

watchEffect(() => {
  document.title = catalog.value[chapterIndex.value]?.title || document.title
})

const saveReadingBookProgressToBrowser = (index: number, pos: number) => {
  chapterIndex.value = index
  chapterPos.value = pos
}

const onVisibilityChange = () => {
  const _bookProgress = bookProgress.value
  if (document.visibilityState == 'hidden' && _bookProgress) {
    store.saveBookProgress()
  }
}

const toNextChapter = () => {
  store.setContentLoading(true)
  const index = chapterIndex.value + 1
  if (typeof catalog.value[index] !== 'undefined') {
    toast.info('下一章')
    getContent(index)
    store.saveBookProgress()
  } else {
    toast.error('本章是最后一章')
  }
}
const toPreChapter = () => {
  store.setContentLoading(true)
  const index = chapterIndex.value - 1
  if (typeof catalog.value[index] !== 'undefined') {
    toast.info('上一章')
    getContent(index)
    store.saveBookProgress()
  } else {
    toast.error('本章是第一章')
  }
}

let canJump = true
const handleKeyPress = (event: KeyboardEvent) => {
  if (!canJump) return
  switch (event.key) {
    case 'ArrowLeft':
      event.stopPropagation()
      event.preventDefault()
      toPreChapter()
      break
    case 'ArrowRight':
      event.stopPropagation()
      event.preventDefault()
      toNextChapter()
      break
    case 'ArrowUp':
      event.stopPropagation()
      event.preventDefault()
      if (document.documentElement.scrollTop === 0) {
        toast.warning('已到达页面顶部')
      } else {
        canJump = false
        jump(0 - document.documentElement.clientHeight + 100, {
          duration: store.config.jumpDuration,
          callback: () => (canJump = true),
        })
      }
      break
    case 'ArrowDown':
      event.stopPropagation()
      event.preventDefault()
      if (
        document.documentElement.clientHeight +
          document.documentElement.scrollTop ===
        document.documentElement.scrollHeight
      ) {
        toast.warning('已到达页面底部')
      } else {
        canJump = false
        jump(document.documentElement.clientHeight - 100, {
          duration: store.config.jumpDuration,
          callback: () => (canJump = true),
        })
      }
      break
  }
}

const ignoreKeyPress = (event: KeyboardEvent) => {
  if (event.key === 'ArrowUp' || event.key === 'ArrowDown') {
    event.preventDefault()
    event.stopPropagation()
  }
}

// 书源 jsLib 动态挂载管理
let jsLibGeneration = 0
let isComponentMounted = false
const currentJsLibElements: HTMLElement[] = []

const cleanJsLib = () => {
  document.querySelectorAll('[data-legado-jslib]').forEach(el => el.remove())
  currentJsLibElements.length = 0
  if ((window as any).__LEGADO_SOURCE__) {
    delete (window as any).__LEGADO_SOURCE__
  }
}

const loadSourceJsLib = async (originUrl: string) => {
  cleanJsLib()
  if (!originUrl || originUrl.startsWith('loc_')) return

  const currentGen = ++jsLibGeneration

  try {
    let source: any = undefined
    try {
      const resp = await API.getSource(originUrl)
      if (resp.data.isSuccess && resp.data.data) {
        source = resp.data.data
      }
    } catch {
      // ignore
    }

    if (!source) {
      const allResp = await API.getSources()
      if (allResp.data.isSuccess && Array.isArray(allResp.data.data)) {
        source = allResp.data.data.find((s: any) => s.bookSourceUrl === originUrl)
      }
    }

    if (!isComponentMounted || currentGen !== jsLibGeneration) return

    if (source) {
      currentBookSource.value = source
      bookSourceType.value = source.bookSourceType
    }

    const jsLib = source?.jsLib?.trim()
    if (!jsLib) return

    ;(window as any).__LEGADO_SOURCE__ = {
      bookSourceUrl: source?.bookSourceUrl,
      bookSourceName: source?.bookSourceName,
      bookSourceGroup: source?.bookSourceGroup,
    }

    console.log(`[Legado Web] 正在加载书源《${source?.bookSourceName || originUrl}》的 jsLib`)

    // 1. 若包含完整 <script> 标签
    if (/<script[^>]*>/i.test(jsLib)) {
      const parser = new DOMParser()
      const doc = parser.parseFromString(jsLib, 'text/html')
      const scripts = doc.querySelectorAll('script')
      scripts.forEach(s => {
        if (!isComponentMounted || currentGen !== jsLibGeneration) return
        const scriptEl = document.createElement('script')
        scriptEl.setAttribute('data-legado-jslib', 'true')
        if (s.src) {
          scriptEl.src = s.src
          scriptEl.async = false
        } else {
          scriptEl.textContent = s.textContent
        }
        document.head.appendChild(scriptEl)
        currentJsLibElements.push(scriptEl)
      })
      return
    }

    // 2. 若是纯脚本 URL 列表
    const lines = jsLib.split('\n').map((l: string) => l.trim()).filter(Boolean)
    const isAllUrls = lines.length > 0 && lines.every((l: string) => /^https?:\/\//i.test(l))
    if (isAllUrls) {
      lines.forEach((url: string) => {
        if (!isComponentMounted || currentGen !== jsLibGeneration) return
        const scriptEl = document.createElement('script')
        scriptEl.setAttribute('data-legado-jslib', 'true')
        scriptEl.src = url
        scriptEl.async = false
        document.head.appendChild(scriptEl)
        currentJsLibElements.push(scriptEl)
      })
      return
    }

    // 3. 否则作为内联 JS 脚本动态执行注入
    if (!isComponentMounted || currentGen !== jsLibGeneration) return
    const scriptEl = document.createElement('script')
    scriptEl.setAttribute('data-legado-jslib', 'true')
    scriptEl.type = 'text/javascript'
    scriptEl.textContent = `
      try {
        ${jsLib}
      } catch (err) {
        console.error('[Legado jsLib 执行错误]:', err);
      }
    `
    document.head.appendChild(scriptEl)
    currentJsLibElements.push(scriptEl)
  } catch (e) {
    console.warn('[Legado Web] 书源 jsLib 加载失败:', e)
  }
}

onMounted(async () => {
  isComponentMounted = true
  await store.loadWebConfig()
  const bookUrl = sessionStorage.getItem('bookUrl')
  const name = sessionStorage.getItem('bookName')
  const author = sessionStorage.getItem('bookAuthor')
  const chapterIndex = Number(sessionStorage.getItem('chapterIndex') || 0)
  const chapterPos = Number(sessionStorage.getItem('chapterPos') || 0)
  const isSeachBook = sessionStorage.getItem('isSeachBook') === 'true'
  const origin = sessionStorage.getItem('bookOrigin') || store.shelf.find(b => b.bookUrl === bookUrl)?.origin || ''

  const storedBookType = Number(sessionStorage.getItem('bookType') || 0)
  const storedBookCover = sessionStorage.getItem('bookCover') || ''
  const shelfBook = store.shelf.find(b => b.bookUrl === bookUrl)

  bookType.value = storedBookType || shelfBook?.type || 0
  const cover = storedBookCover || shelfBook?.coverUrl || ''

  if (origin) {
    loadSourceJsLib(origin)
  }

  if (isNullOrBlank(bookUrl) || isNullOrBlank(name) || author === null) {
    toast.warning('书籍信息为空，即将自动返回书架页面...')
    return setTimeout(toShelf, 500)
  }
  const book: typeof store.readingBook = {
    bookUrl: bookUrl!,
    name: name!,
    author: author!,
    chapterIndex,
    chapterPos,
    isSeachBook,
    type: bookType.value,
    coverUrl: cover,
    origin,
  }
  onResize()
  window.addEventListener('resize', onResize)
  loadingWrapper(
    store.loadWebCatalog(book).then(chapters => {
      store.setReadingBook(book)
      getContent(chapterIndex, true, chapterPos)
      window.addEventListener('keyup', handleKeyPress)
      window.addEventListener('keydown', ignoreKeyPress)
      document.addEventListener('visibilitychange', onVisibilityChange)
      scrollObserver = new IntersectionObserver(onReachBottom, {
        rootMargin: '-100% 0% 20% 0%',
      })
      if (infiniteLoading.value === true && effectiveMode.value === 'text') {
        scrollObserver.observe(loading.value)
      }
      document.title = '...'
      document.title = (name as string) + ' | ' + chapters[chapterIndex].title
    }),
  )
})

onUnmounted(() => {
  isComponentMounted = false
  jsLibGeneration++
  contentGeneration++
  cleanJsLib()
  window.removeEventListener('keyup', handleKeyPress)
  window.removeEventListener('keydown', ignoreKeyPress)
  window.removeEventListener('resize', onResize)
  document.removeEventListener('visibilitychange', onVisibilityChange)
  readSettingsVisible.value = false
  popCataVisible.value = false
  scrollObserver?.disconnect()
  scrollObserver = null
})

let isConfirming = false
const addToBookShelfConfirm = async () => {
  if (isConfirming) return
  const book = store.readingBook
  const isAlreadyInShelf = store.shelf.some(b => b.bookUrl === book.bookUrl)
  if (isAlreadyInShelf) {
    book.isSeachBook = false
    isSeachBook.value = false
    sessionStorage.removeItem('isSeachBook')
    return
  }
  if (book.isSeachBook === true || isSeachBook.value === true) {
    isConfirming = true
    try {
      try {
        await msgbox.confirm(
          `是否将《${book.name}》放入书架？`,
          '放入书架',
          { closeOnHashChange: false },
        )
      } catch {
        const resp = await API.deleteBook(book)
        if (!resp.data.isSuccess) {
          toast.error(resp.data.errorMsg || '清理临时书籍失败')
          return
        }
        book.isSeachBook = false
        isSeachBook.value = false
        sessionStorage.removeItem('isSeachBook')
        return
      }

      try {
        const resp = await API.saveBook(book)
        if (!resp.data.isSuccess) throw new Error(resp.data.errorMsg || '保存书籍失败')
        book.isSeachBook = false
        isSeachBook.value = false
        sessionStorage.removeItem('isSeachBook')
        await store.loadBookShelf(undefined, true)
      } catch (error: any) {
        toast.error(error?.message || '保存书籍失败，临时记录已保留')
      }
    } finally {
      isConfirming = false
    }
  }
}
onBeforeRouteLeave(async () => {
  window.removeEventListener('keyup', handleKeyPress)
  await addToBookShelfConfirm()
})
</script>

<style lang="scss" scoped>
.chapter-wrapper {
  padding: 0 4%;
  overflow-x: hidden;

  .no-point {
    pointer-events: none;
  }

  .tool-bar {
    position: fixed;
    top: 0;
    left: 50%;
    z-index: 100;

    .tools {
      display: flex;
      flex-direction: column;

      .tool-icon {
        font-size: 18px;
        width: 58px;
        height: 48px;
        text-align: center;
        padding-top: 12px;
        cursor: pointer;
        outline: none;

        .iconfont {
          font-family: iconfont;
          width: 16px;
          height: 16px;
          font-size: 16px;
          margin: 0 auto 6px;
        }

        .icon-text {
          font-size: 12px;
        }
      }
    }
  }

  .read-bar {
    position: fixed;
    bottom: 0;
    right: 50%;
    z-index: 100;

    .tools {
      display: flex;
      flex-direction: column;

      .tool-icon {
        font-size: 18px;
        width: 42px;
        height: 31px;
        padding-top: 12px;
        text-align: center;
        align-items: center;
        cursor: pointer;
        outline: none;
        margin-top: -1px;

        .iconfont {
          font-family: iconfont;
          width: 16px;
          height: 16px;
          font-size: 16px;
          margin: 0 auto 6px;
        }
      }
    }
  }

  .chapter {
    font-family: 'Microsoft YaHei', PingFangSC-Regular, HelveticaNeue-Light,
      'Helvetica Neue Light', sans-serif;
    text-align: left;
    padding: 0 65px;
    min-height: 100vh;
    width: 670px;
    margin: 0 auto;

    &.comic-mode {
      padding: 0 !important;
      border: none !important;
      width: 100% !important;
      box-sizing: border-box;
      margin: 0 auto;
    }

    &.audio-mode,
    &.video-mode {
      padding: 0 !important;
      border: none !important;
      width: 100% !important;
      max-width: 100% !important;
      box-sizing: border-box;
      margin: 0 auto;
    }

    .content {
      font-size: 18px;
      line-height: 1.8;
      font-family: 'Microsoft YaHei', PingFangSC-Regular, HelveticaNeue-Light,
        'Helvetica Neue Light', sans-serif;

      .bottom-bar,
      .top-bar {
        height: 64px;

        &.compact {
          height: 16px;
        }
      }
    }
  }
}

.day {
  .popup {
    box-shadow: 0 2px 4px rgba(0, 0, 0, 0.12), 0 0 6px rgba(0, 0, 0, 0.04);
  }

  .tool-icon {
    border: 1px solid rgba(0, 0, 0, 0.1);
    margin-top: -1px;
    color: #000;

    .icon-text {
      color: rgba(0, 0, 0, 0.4);
    }
  }

  .chapter {
    border: 1px solid #d8d8d8;
    color: #262626;

    &.comic-mode,
    &.audio-mode,
    &.video-mode {
      border: none !important;
    }
  }
}

.night {
  .popup {
    box-shadow: 0 2px 4px rgba(0, 0, 0, 0.48), 0 0 6px rgba(0, 0, 0, 0.16);
  }

  .tool-icon {
    border: 1px solid #444;
    margin-top: -1px;
    color: #666;

    .icon-text {
      color: #666;
    }
  }

  .chapter {
    border: 1px solid #444;
    color: #666;

    &.comic-mode,
    &.audio-mode,
    &.video-mode {
      border: none !important;
    }
  }
}

@media screen and (max-width: 776px) {
  .chapter-wrapper {
    padding: 0;

    .tool-bar {
      left: 0;
      width: 100vw;
      margin-left: 0 !important;

      .tools {
        flex-direction: row;
        justify-content: space-between;

        .tool-icon {
          border: none;
        }
      }
    }

    .read-bar {
      right: 0;
      width: 100vw;
      margin-right: 0 !important;

      .tools {
        flex-direction: row;
        justify-content: space-between;
        padding: 0 15px;

        .tool-icon {
          border: none;
          width: auto;

          .iconfont {
            display: inline-block;
          }
        }
      }
    }

    .chapter {
      width: 100vw !important;
      padding: 0 20px;
      box-sizing: border-box;
    }
  }
}
</style>
