<template>
  <div class="comic-reader" ref="comicContainerRef" @click="handleScreenClick">
    <!-- 章节标题栏 -->
    <div class="comic-header" v-if="title">
      <h2 class="comic-title">{{ title }}</h2>
    </div>

    <!-- 图片垂直瀑布流：无缝拼接，宽度受阅读宽度严格限制 -->
    <div class="comic-waterfall" :style="{ maxWidth: containerMaxWidth }">
      <div
        v-for="(item, idx) in imageItems"
        :key="idx"
        class="comic-page-wrapper"
        :data-index="idx"
      >
        <img
          v-if="item.shouldLoad"
          :src="item.currentSrc"
          :alt="'第 ' + (idx + 1) + ' 页'"
          class="comic-img"
          :class="{ loaded: item.isLoaded }"
          loading="lazy"
          @load="onImageLoad(idx)"
          @error="onImageError(idx)"
        />

        <!-- 占位骨架 / 懒加载等待 -->
        <div v-if="!item.isLoaded && !item.loadFailed" class="comic-placeholder">
          <div class="comic-placeholder-content">
            <span class="comic-page-num">第 {{ idx + 1 }} 页</span>
            <span class="comic-loading-spinner" v-if="item.shouldLoad">正在载入图片...</span>
            <span class="comic-waiting-text" v-else>滑动至此自动载入</span>
          </div>
        </div>

        <!-- 加载失败重试提示 -->
        <div v-if="item.loadFailed" class="comic-error-placeholder">
          <p>第 {{ idx + 1 }} 页加载失败</p>
          <button class="comic-retry-btn" @click.stop="retryImage(idx)">点击重试</button>
        </div>
      </div>

      <!-- 图片为空兜底 -->
      <div v-if="imageItems.length === 0" class="empty-hint">
        <p>未提取到图片内容或章节正在加载中...</p>
        <button class="reparse-comic-btn" @click.stop="emit('reParse')">刷新正文内容</button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import API, { toAbsoluteUrl, legado_http_entry_point } from '@api'
import { isLegadoUrl } from '@/utils/utils'

interface ImageItem {
  rawUrl: string
  currentSrc: string
  hasProxied: boolean
  shouldLoad: boolean
  isLoaded: boolean
  loadFailed: boolean
}

const props = defineProps<{
  chapterIndex: number
  title: string
  contents: string[] | string
  bookUrl: string
  origin?: string
  hasImageDecode?: boolean
  readWidth?: number | string
}>()

const emit = defineEmits<{
  (e: 'toggleToolbar'): void
  (e: 'reParse'): void
  (e: 'prevChapter'): void
  (e: 'nextChapter'): void
}>()

const comicContainerRef = ref<HTMLElement>()
const imageItems = ref<ImageItem[]>([])

const containerMaxWidth = computed(() => {
  if (typeof props.readWidth === 'number') {
    return `${props.readWidth}px`
  }
  return props.readWidth || '900px'
})

// 规范化图片 URL
const normalizeUrl = (raw: string): string => {
  if (!raw) return ''
  let str = raw.trim()
  if (str.startsWith('//')) {
    str = 'https:' + str
  }
  return str
}

// 从 contents 中解析所有图片 URL
const parseImageUrls = (content: string[] | string): string[] => {
  const result: string[] = []
  if (!content) return result

  const rawList: string[] = Array.isArray(content) ? content : [content]
  const fullText = rawList.join('\n')

  // 1. 匹配包含在 <img ... src="..."> 的图片
  const imgRegex = /<img[^>]*src=['"]([^'"]*(?:['"][^>]+\})?)['"][^>]*>/gi
  let match: RegExpExecArray | null
  while ((match = imgRegex.exec(fullText)) !== null) {
    const url = normalizeUrl(match[1])
    if (url) result.push(url)
  }

  // 2. 若无 img 标签，解析纯链接行
  if (result.length === 0) {
    for (const item of rawList) {
      const lines = String(item).split('\n').map(l => l.trim()).filter(Boolean)
      for (const line of lines) {
        const url = normalizeUrl(line)
        if (
          /^https?:\/\//i.test(url) ||
          url.startsWith('data:image/') ||
          url.startsWith('blob:') ||
          url.startsWith('/image?') ||
          url.startsWith('image?') ||
          /\.(jpe?g|png|gif|webp|bmp|avif)(\?|$)/i.test(url)
        ) {
          result.push(url)
        }
      }
    }
  }

  return result
}

const images = computed(() => parseImageUrls(props.contents))

const localHasImageDecode = ref(false)
const effectiveHasImageDecode = computed(() => {
  return Boolean(props.hasImageDecode || localHasImageDecode.value)
})

watch(
  () => props.origin,
  async newOrigin => {
    if (!newOrigin || props.hasImageDecode !== undefined) return
    try {
      const res = await API.getSource(newOrigin)
      const source = res.data.data as any
      const decode = source?.ruleContent?.imageDecode || source?.contentRule?.imageDecode
      if (res.data.isSuccess && decode?.trim()) {
        localHasImageDecode.value = true
      }
    } catch {
      // ignore
    }
  },
  { immediate: true },
)

let observer: IntersectionObserver | null = null

const setupObserver = () => {
  if (observer) {
    observer.disconnect()
    observer = null
  }

  if (typeof IntersectionObserver === 'undefined') {
    imageItems.value.forEach(item => {
      item.shouldLoad = true
    })
    return
  }

  observer = new IntersectionObserver(
    entries => {
      entries.forEach(entry => {
        if (entry.isIntersecting) {
          const idx = Number((entry.target as HTMLElement).dataset.index)
          if (!isNaN(idx) && imageItems.value[idx]) {
            imageItems.value[idx].shouldLoad = true
            observer?.unobserve(entry.target)
          }
        }
      })
    },
    {
      rootMargin: '1000px 0px 1000px 0px',
      threshold: 0.01,
    },
  )

  nextTick(() => {
    const els = document.querySelectorAll('.comic-page-wrapper')
    els.forEach(el => observer?.observe(el))
  })
}

// 初始化图片列表
const initImages = () => {
  const width = typeof props.readWidth === 'number' ? props.readWidth : 1080
  const urls = images.value
  const forceProxy = effectiveHasImageDecode.value

  imageItems.value = urls.map((url, idx) => {
    // 1. 若后端已在中转格式（/image?...），直接使用服务端入口点转为绝对路径
    if (url.startsWith('/image?') || url.startsWith('image?')) {
      return {
        rawUrl: url,
        currentSrc: toAbsoluteUrl(url),
        hasProxied: true,
        shouldLoad: idx < 3,
        isLoaded: false,
        loadFailed: false,
      }
    }
    const entryPoint = legado_http_entry_point
    if (typeof entryPoint === 'string' && entryPoint && url.startsWith(entryPoint)) {
      return {
        rawUrl: url,
        currentSrc: url,
        hasProxied: true,
        shouldLoad: idx < 3,
        isLoaded: false,
        loadFailed: false,
      }
    }

    // 2. 包含请求头等参数的 Legado URL 或 源配置了正文图片解密 (imageDecode) 时，走后端图片代理
    if ((forceProxy || isLegadoUrl(url)) && props.bookUrl) {
      return {
        rawUrl: url,
        currentSrc: API.getProxyImageUrl(props.bookUrl, url),
        hasProxied: true,
        shouldLoad: idx < 3,
        isLoaded: false,
        loadFailed: false,
      }
    }

    // 3. 普通图片且无解密需求时优先直连
    let clean = url
    const commaIdx = clean.indexOf(',{')
    if (commaIdx > 0) {
      clean = clean.substring(0, commaIdx).trim()
    }
    return {
      rawUrl: url,
      currentSrc: clean,
      hasProxied: false,
      shouldLoad: idx < 3,
      isLoaded: false,
      loadFailed: false,
    }
  })

  setupObserver()
}

const onImageLoad = (index: number) => {
  const item = imageItems.value[index]
  if (item) {
    item.isLoaded = true
    item.loadFailed = false
  }
}

// 图片加载失败时对齐 ChapterContent.vue 走后端代理，若仍失败则提示重试
const onImageError = (index: number) => {
  const item = imageItems.value[index]
  if (!item) return

  if (!item.hasProxied && props.bookUrl) {
    item.hasProxied = true
    const width = typeof props.readWidth === 'number' ? props.readWidth : 1080
    item.currentSrc = API.getProxyImageUrl(props.bookUrl, item.rawUrl, width)
  } else {
    item.loadFailed = true
  }
}

const retryImage = (index: number) => {
  const item = imageItems.value[index]
  if (!item) return
  item.loadFailed = false
  item.isLoaded = false
  item.shouldLoad = true
  const sep = item.currentSrc.includes('?') ? '&' : '?'
  item.currentSrc = `${item.currentSrc.replace(/[&?]_retry=\d+/, '')}${sep}_retry=${Date.now()}`
}

watch(
  [() => props.contents, effectiveHasImageDecode],
  () => {
    initImages()
  },
  { immediate: true, deep: true },
)

onUnmounted(() => {
  observer?.disconnect()
  observer = null
})

// 屏幕点击交互：左侧向上滚（顶部则上一章），右侧向下滚（底部则下一章），中间呼出菜单（左中右交互）
const handleScreenClick = (e: MouseEvent) => {
  if ((e.target as HTMLElement).closest('button, .reparse-comic-btn, .comic-retry-btn')) {
    return
  }

  const clickX = e.clientX
  const windowWidth = window.innerWidth
  const leftThreshold = windowWidth * 0.33
  const rightThreshold = windowWidth * 0.67

  if (clickX < leftThreshold) {
    // 左侧：向上滚动翻页或上一章
    const currentScroll = window.scrollY || document.documentElement.scrollTop
    if (currentScroll <= 20) {
      emit('prevChapter')
    } else {
      window.scrollBy({ top: -window.innerHeight * 0.8, behavior: 'smooth' })
    }
  } else if (clickX > rightThreshold) {
    // 右侧：向下滚动翻页或下一章
    const maxScroll = document.documentElement.scrollHeight - window.innerHeight
    const currentScroll = window.scrollY || document.documentElement.scrollTop
    if (currentScroll >= maxScroll - 20) {
      emit('nextChapter')
    } else {
      window.scrollBy({ top: window.innerHeight * 0.8, behavior: 'smooth' })
    }
  } else {
    // 中间：呼出/隐藏工具栏
    emit('toggleToolbar')
  }
}
</script>

<style lang="scss" scoped>
.comic-reader {
  width: 100%;
  box-sizing: border-box;
  display: block;
  user-select: none;
  cursor: pointer;
  padding-bottom: 24px;

  .comic-header {
    width: 100%;
    padding: 24px 16px 16px;
    box-sizing: border-box;
    text-align: center;

    .comic-title {
      margin: 0;
      font-size: 20px;
      font-weight: 600;
      color: inherit;
    }
  }

  .comic-waterfall {
    width: 100%;
    margin: 0 auto;
    padding: 0;
    display: block;
    box-sizing: border-box;

    .comic-page-wrapper {
      width: 100%;
      position: relative;
      margin: 0;
      padding: 0;
      line-height: 0;

      .comic-img {
        width: 100% !important;
        max-width: 100% !important;
        height: auto !important;
        max-height: none !important;
        min-height: 0 !important;
        display: block !important;
        margin: 0 auto;
        padding: 0;
        border: none;
        outline: none;
        background: transparent;
        box-shadow: none;
        border-radius: 0;
        vertical-align: bottom;
      }

      .comic-placeholder {
        width: 100%;
        min-height: 480px;
        display: flex;
        align-items: center;
        justify-content: center;
        background: rgba(128, 128, 128, 0.06);
        border-bottom: 1px dashed rgba(128, 128, 128, 0.15);
        box-sizing: border-box;
        line-height: normal;

        .comic-placeholder-content {
          display: flex;
          flex-direction: column;
          align-items: center;
          gap: 8px;
          opacity: 0.6;
          font-size: 14px;
        }

        .comic-page-num {
          font-weight: 500;
        }

        .comic-loading-spinner {
          font-size: 12px;
        }

        .comic-waiting-text {
          font-size: 12px;
          opacity: 0.7;
        }
      }

      .comic-error-placeholder {
        width: 100%;
        min-height: 240px;
        display: flex;
        flex-direction: column;
        align-items: center;
        justify-content: center;
        gap: 12px;
        background: rgba(244, 67, 54, 0.05);
        color: #f44336;
        font-size: 14px;
        line-height: normal;

        .comic-retry-btn {
          padding: 6px 16px;
          border-radius: 16px;
          border: 1px solid #f44336;
          background: transparent;
          color: #f44336;
          cursor: pointer;
          font-size: 13px;
          transition: all 0.2s;

          &:hover {
            background: #f44336;
            color: #fff;
          }
        }
      }
    }

    .empty-hint {
      padding: 80px 20px;
      text-align: center;
      opacity: 0.85;
      font-size: 15px;
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 16px;

      .reparse-comic-btn {
        padding: 8px 18px;
        border-radius: 18px;
        border: 1px solid #2196f3;
        background: rgba(33, 150, 243, 0.1);
        color: #2196f3;
        font-size: 14px;
        cursor: pointer;
        transition: all 0.2s;

        &:hover {
          background: #2196f3;
          color: #ffffff;
        }
      }
    }
  }
}
</style>
