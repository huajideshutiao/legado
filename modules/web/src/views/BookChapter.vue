<template>
  <div
    class="chapter-wrapper"
    :style="bodyTheme"
    :class="{ night: isNight, day: !isNight }"
    @click="showToolBar = !showToolBar"
  >
    <div class="tool-bar" :style="leftBarTheme">
      <div class="tools">
        <div class="tool-icon" @click.stop="popCataVisible = !popCataVisible">
          <div class="iconfont">&#58905;</div>
          <div class="icon-text">目录</div>
        </div>
        <div class="tool-icon" @click.stop="readSettingsVisible = !readSettingsVisible">
          <div class="iconfont">&#58971;</div>
          <div class="icon-text">设置</div>
        </div>
        <div class="tool-icon" @click="toShelf">
          <div class="iconfont">&#58892;</div>
          <div class="icon-text">书架</div>
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
    <div class="read-bar" :style="rightBarTheme">
      <div class="tools">
        <div class="tool-icon" :class="{ 'no-point': noPoint }" @click="toPreChapter">
          <div class="iconfont">&#58920;</div>
          <span v-if="miniInterface">上一章</span>
        </div>
        <div class="tool-icon" :class="{ 'no-point': noPoint }" @click="toNextChapter">
          <span v-if="miniInterface">下一章</span>
          <div class="iconfont">&#58913;</div>
        </div>
      </div>
    </div>

    <div v-if="popCataVisible" class="web-dialog-overlay" @click.self="popCataVisible = false">
      <div class="web-dialog popup" :style="{ background: popupColor, maxWidth: popupWidth + 'px' }">
        <PopCatalog @getContent="getContent" />
      </div>
    </div>

    <div v-if="readSettingsVisible" class="web-dialog-overlay" @click.self="readSettingsVisible = false">
      <div class="web-dialog popup" :style="{ background: popupColor, maxWidth: popupWidth + 'px' }">
        <read-settings />
      </div>
    </div>

    <div class="chapter" ref="content" :style="chapterTheme">
      <div class="content">
        <div class="top-bar" ref="top"></div>
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
            @readedLengthChange="onReadedLengthChange"
            v-if="showContent"
          />
        </div>
        <div class="loading" ref="loading"></div>
        <div class="bottom-bar" ref="bottom"></div>
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

const infiniteLoading = computed(() => store.config.infiniteLoading)
let scrollObserver: IntersectionObserver | null
const loading = ref()
watchEffect(() => {
  if (!infiniteLoading.value) {
    scrollObserver?.disconnect()
  } else {
    scrollObserver?.observe(loading.value)
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
  return { background: chapterColor.value, width: readWidth.value }
})
const showToolBar = ref(false)
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

const chapterData = ref<{ index: number; content: string[]; title: string }[]>([])
const noPoint = ref(true)
const getContent = (index: number, reloadChapter = true, chapterPos = 0) => {
  if (reloadChapter) {
    store.setShowContent(false)
    jump(top.value, { duration: 0 })
    saveReadingBookProgressToBrowser(index, chapterPos)
    chapterData.value = []
  }
  const bookUrl = store.readingBook.bookUrl
  const { title, index: chapterIndex } = catalog.value[index]

  loadingWrapper(
    API.getBookContent(bookUrl, chapterIndex).then(
      res => {
        if (res.data.isSuccess) {
          const data = res.data.data
          const content = data.split(/\n+/)
          chapterData.value.push({ index, content, title })
          if (reloadChapter) toChapterPos(chapterPos)
        } else {
          toast.error(res.data.errorMsg)
          const content = [res.data.errorMsg]
          chapterData.value.push({ index, content, title })
        }
        store.setContentLoading(true)
        noPoint.value = false
        store.setShowContent(true)
        if (!res.data.isSuccess) {
          throw res.data
        }
      },
      err => {
        const content = ['获取章节内容失败！']
        chapterData.value.push({ index, content, title })
        store.setShowContent(true)
        throw err
      },
    ),
  )
}

const chapter = ref()
const chapterRef = ref()
const toChapterPos = (pos: number) => {
  nextTick(() => {
    if (chapterRef.value.length === 1)
      chapterRef.value[0].scrollToReadedLength(pos)
  })
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

onMounted(async () => {
  await store.loadWebConfig()
  const bookUrl = sessionStorage.getItem('bookUrl')
  const name = sessionStorage.getItem('bookName')
  const author = sessionStorage.getItem('bookAuthor')
  const chapterIndex = Number(sessionStorage.getItem('chapterIndex') || 0)
  const chapterPos = Number(sessionStorage.getItem('chapterPos') || 0)
  const isSeachBook = sessionStorage.getItem('isSeachBook') === 'true'
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
      if (infiniteLoading.value === true) scrollObserver.observe(loading.value)
      document.title = '...'
      document.title = (name as string) + ' | ' + chapters[chapterIndex].title
    }),
  )
})

onUnmounted(() => {
  window.removeEventListener('keyup', handleKeyPress)
  window.removeEventListener('keydown', ignoreKeyPress)
  window.removeEventListener('resize', onResize)
  document.removeEventListener('visibilitychange', onVisibilityChange)
  readSettingsVisible.value = false
  popCataVisible.value = false
  scrollObserver?.disconnect()
  scrollObserver = null
})

const addToBookShelfConfirm = async () => {
  const book = store.readingBook
  if (book.isSeachBook === true) {
    try {
      await msgbox.confirm(
        `是否将《${book.name}》放入书架？`,
        '放入书架',
        { closeOnHashChange: false },
      )
      isSeachBook.value = false
    } catch {
      await API.deleteBook(book)
      sessionStorage.removeItem('isSeachBook')
    }
  }
}
onBeforeRouteLeave(async (to, from, next) => {
  console.log('onBeforeRouteLeave')
  window.removeEventListener('keyup', handleKeyPress)
  await addToBookShelfConfirm()
  next()
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

    .content {
      font-size: 18px;
      line-height: 1.8;
      font-family: 'Microsoft YaHei', PingFangSC-Regular, HelveticaNeue-Light,
        'Helvetica Neue Light', sans-serif;

      .bottom-bar,
      .top-bar {
        height: 64px;
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
