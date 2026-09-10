<template>
  <div class="book-info-page" :class="{ night: isNight }">
    <!-- 顶部导航栏 -->
    <header class="info-top-bar">
      <button class="top-btn" type="button" title="返回" @click="goBack">
        <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 19l-7-7 7-7" />
        </svg>
      </button>
      <div class="top-title">书籍详情</div>
      <button
        class="top-btn"
        type="button"
        title="刷新"
        :disabled="isRefreshing"
        @click="refreshBookInfo"
      >
        <svg
          viewBox="0 0 24 24"
          width="20"
          height="20"
          fill="none"
          stroke="currentColor"
          :class="{ 'spin-icon': isRefreshing }"
        >
          <path
            stroke-linecap="round"
            stroke-linejoin="round"
            stroke-width="2"
            d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15"
          />
        </svg>
      </button>
    </header>

    <!-- 主体滚动区 -->
    <main class="info-content" v-if="book">
      <!-- 头部书籍卡片 (封面 + 信息) -->
      <section class="book-header-card">
        <div class="cover-wrapper">
          <img
            class="book-cover"
            :src="coverUrl"
            @error.once="proxyImage"
            alt=""
          />
        </div>
        <div class="book-main-info">
          <h1 class="book-name" :title="book.name">{{ book.name }}</h1>
          <div class="author-row" @click="searchAuthor">
            <span class="author-label">作者：</span>
            <span class="author-name">{{ displayAuthor || '未知作者' }}</span>
          </div>
          <div class="meta-row">
            <span class="meta-item" v-if="displayWordCount">{{ displayWordCount }}</span>
            <span class="meta-dot" v-if="displayWordCount && chapterCountText">•</span>
            <span class="meta-item">{{ chapterCountText }}</span>
          </div>
          <div class="latest-chapter-row" v-if="latestChapterText" :title="latestChapterText">
            <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" class="latest-icon">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" />
            </svg>
            <span class="latest-text">最新：{{ latestChapterText }}</span>
          </div>
          <div class="read-progress-row" v-if="durChapterText">
            <span class="progress-badge">已读</span>
            <span class="progress-text">{{ durChapterText }}</span>
          </div>
        </div>
      </section>

      <!-- 动作行: 4 个功能卡片 -->
      <section class="actions-row">
        <div class="action-cell" @click="searchAuthor">
          <div class="action-icon-box">
            <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M16 7a4 4 0 11-8 0 4 4 0 018 0zM12 14a7 7 0 00-7 7h14a7 7 0 00-7-7z" />
            </svg>
          </div>
          <div class="action-label">{{ displayAuthor || '作者' }}</div>
          <div class="action-sub">作者</div>
        </div>
        <div class="action-cell" @click="showOriginInfo">
          <div class="action-icon-box">
            <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 12a9 9 0 01-9 9m9-9a9 9 0 00-9-9m9 9H3m9 9a9 9 0 01-9-9m9 9c1.657 0 3-4.03 3-9s-1.343-9-3-9m0 18c-1.657 0-3-4.03-3-9s1.343-9 3-9m-9 9a9 9 0 019-9" />
            </svg>
          </div>
          <div class="action-label" :title="originText">{{ originText }}</div>
          <div class="action-sub">来源</div>
        </div>
        <div class="action-cell">
          <div class="action-icon-box">
            <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 11H5m14 0a2 2 0 012 2v6a2 2 0 01-2 2H5a2 2 0 01-2-2v-6a2 2 0 012-2m14 0V9a2 2 0 00-2-2M5 11V9a2 2 0 012-2m0 0V5a2 2 0 012-2h6a2 2 0 012 2v2M7 7h10" />
            </svg>
          </div>
          <div class="action-label">{{ groupName }}</div>
          <div class="action-sub">分组</div>
        </div>
        <div class="action-cell" @click="showCatalogDialog = true">
          <div class="action-icon-box">
            <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 6h16M4 10h16M4 14h16M4 18h16" />
            </svg>
          </div>
          <div class="action-label">{{ catalog.length ? catalog.length + ' 章' : '查看目录' }}</div>
          <div class="action-sub">目录</div>
        </div>
      </section>

      <!-- 分类标签 -->
      <section class="section-card tags-section" v-if="tagList.length > 0">
        <div class="section-header">
          <span class="section-title">分类标签</span>
        </div>
        <div class="tag-flow">
          <span
            class="tag-chip"
            v-for="tag in tagList"
            :key="tag.raw"
            @click="searchTag(tag)"
          >
            {{ tag.title }}
          </span>
        </div>
      </section>

      <!-- 简介：直接完全展开，不折叠 -->
      <section class="section-card intro-section">
        <div class="section-header">
          <span class="section-title">书籍简介</span>
        </div>
        <div class="intro-body">
          {{ introText || '暂无简介' }}
        </div>
      </section>
    </main>

    <!-- 底部悬浮双按钮栏 -->
    <footer class="info-bottom-bar" v-if="book">
      <button
        class="shelf-btn"
        :class="{ in: inBookshelf }"
        type="button"
        :disabled="isOperatingShelf"
        @click="toggleShelf"
      >
        <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor">
          <path
            v-if="inBookshelf"
            stroke-linecap="round"
            stroke-linejoin="round"
            stroke-width="2"
            d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"
          />
          <path
            v-else
            stroke-linecap="round"
            stroke-linejoin="round"
            stroke-width="2"
            d="M12 4v16m8-8H4"
          />
        </svg>
        <span>{{ inBookshelf ? '移出书架' : '放入书架' }}</span>
      </button>
      <button class="read-btn" type="button" @click="startReading()">
        <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 6.253v13m0-13C10.832 5.477 9.246 5 7.5 5S4.168 5.477 3 6.253v13C4.168 18.477 5.754 18 7.5 18s3.332.477 4.5 1.253m0-13C13.168 5.477 14.754 5 16.5 5c1.747 0 3.332.477 4.5 1.253v13C19.832 18.477 18.247 18 16.5 18c-1.746 0-3.332.477-4.5 1.253" />
        </svg>
        <span>{{ durChapterIndex > 0 || durChapterText ? '继续阅读' : '开始阅读' }}</span>
      </button>
    </footer>

    <!-- 复用书籍目录弹窗 -->
    <BookCatalogDialog
      v-model:visible="showCatalogDialog"
      :catalog="catalog"
      :currentChapterIndex="durChapterIndex"
      :isNight="isNight"
      @select="onSelectChapter"
    />
  </div>
</template>

<script setup lang="ts">
defineOptions({ name: 'BookInfo' })

import { ref, computed, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useBookStore } from '@/store'
import API from '@api'
import { toast } from '@/utils/toast'
import { isLegadoUrl } from '@/utils/utils'
import type { BaseBook, Book, BookChapter, SeachBook } from '@/book'
import BookCatalogDialog from '@/components/BookCatalogDialog.vue'

const router = useRouter()
const route = useRoute()
const store = useBookStore()

const isNight = computed(() => store.isNight)

const book = ref<Book | SeachBook | null>(null)
const catalog = ref<BookChapter[]>([])
const loadingCatalog = ref(false)
const showCatalogDialog = ref(false)
const isOperatingShelf = ref(false)
const isRefreshing = ref(false)

// 初始化书籍对象
const initBook = () => {
  if (store.detailBook) {
    book.value = store.detailBook
    return
  }
  const raw = sessionStorage.getItem('detailBook')
  if (raw) {
    try {
      book.value = JSON.parse(raw)
      store.detailBook = book.value
      return
    } catch (e) {
      console.error('[BookInfo] 解析 detailBook 失败:', e)
    }
  }
  const qUrl = route.query.bookUrl as string
  if (qUrl) {
    const found = store.shelf.find(b => b.bookUrl === qUrl)
    if (found) {
      book.value = found
      store.setDetailBook(found)
      return
    }
  }
}

// 封面取值与代理兜底
const coverUrl = computed(() => {
  if (!book.value) return ''
  const c = book.value.coverUrl
  if (!c) return API.getProxyCoverUrl(book.value.bookUrl)
  return isLegadoUrl(c) ? API.getProxyCoverUrl(c) : c
})

const proxyImage = (evt: Event) => {
  const target = evt.target as HTMLImageElement
  target.src = API.getProxyCoverUrl(target.src)
}

// 来源文案
const originText = computed(() => {
  if (!book.value) return '本地书籍'
  if ('originName' in book.value && book.value.originName) return book.value.originName
  if ('origin' in book.value && book.value.origin) return book.value.origin
  return '本地书籍'
})

// 分组文案
const groupName = computed(() => {
  if (!book.value || !('group' in book.value)) return '未分组'
  const gid = (book.value as Book).group
  const match = store.groups.find(g => g.groupId === gid)
  return match ? match.groupName : '未分组'
})

// 书籍字数
const displayWordCount = computed(() => {
  if (!book.value) return ''
  if ('wordCount' in book.value && book.value.wordCount) {
    return `字数：${book.value.wordCount}`
  }
  if ('chapterWordCountText' in book.value && book.value.chapterWordCountText) {
    return book.value.chapterWordCountText
  }
  return ''
})

// 总章节数文案
const totalChapterCount = computed(() => {
  if (catalog.value.length > 0) return catalog.value.length
  if (book.value && 'totalChapterNum' in book.value && (book.value as Book).totalChapterNum) {
    return (book.value as Book).totalChapterNum
  }
  return 0
})

const chapterCountText = computed(() => {
  const cnt = totalChapterCount.value
  return cnt > 0 ? `共 ${cnt} 章` : '目录未载入'
})

// 最新章节
const latestChapterText = computed(() => {
  if (book.value?.latestChapterTitle) return book.value.latestChapterTitle
  if (catalog.value.length > 0) {
    return catalog.value[catalog.value.length - 1].title
  }
  return ''
})

// 当前阅读进度章节
const durChapterIndex = computed(() => {
  if (book.value && 'durChapterIndex' in book.value) {
    return (book.value as Book).durChapterIndex || 0
  }
  return 0
})

const durChapterText = computed(() => {
  if (book.value && 'durChapterTitle' in book.value && (book.value as Book).durChapterTitle) {
    return (book.value as Book).durChapterTitle
  }
  if (durChapterIndex.value > 0) {
    return `第 ${durChapterIndex.value + 1} 章`
  }
  return ''
})

// 简介文本
const introText = computed(() => {
  if (!book.value?.intro) return ''
  return book.value.intro.replace(/<br\s*\/?>/gi, '\n').replace(/<\/?[^>]+(>|$)/g, '')
})

// 干净展示的作者名
const displayAuthor = computed(() => {
  if (!book.value?.author) return ''
  const raw = book.value.author.trim()
  const part = raw.split('::')[0].trim()
  return part.replace(/^(作者|作\s*者)[：:]\s*/, '').replace(/\s*(著|编著|原著)$/, '').trim()
})

interface BookTagItem {
  raw: string
  title: string
  exploreUrl?: string
}

// 分类标签解析 (支持普通分类与带探索链接的分类，对照 Compose onSearchKind)
const tagList = computed<BookTagItem[]>(() => {
  if (!book.value?.kind) return []
  return book.value.kind
    .split(/[,;\n]/)
    .map(t => t.trim())
    .filter(Boolean)
    .map(t => {
      if (t.includes('::')) {
        const parts = t.split('::')
        return {
          raw: t,
          title: parts[0].trim(),
          exploreUrl: parts.slice(1).join('::').trim(),
        }
      }
      let title = t
      if (title.includes(':') || title.includes('：')) {
        const parts = title.split(/[：:]/)
        title = parts[parts.length - 1].trim()
      }
      return {
        raw: t,
        title,
      }
    })
    .filter(item => Boolean(item.title))
})

// 是否在书架
const inBookshelf = computed(() => {
  if (!book.value) return false
  return store.shelf.some(
    b => b.bookUrl === book.value?.bookUrl || (b.name === book.value?.name && b.author === book.value?.author),
  )
})

// 返回上一页
const goBack = () => {
  if (window.history.length > 1) {
    router.back()
  } else {
    router.push('/shelf')
  }
}

// 加载目录
const loadCatalog = async (forceRefresh = false) => {
  if (!book.value?.bookUrl) return
  loadingCatalog.value = true
  const origin = ('origin' in book.value ? book.value.origin : '') || ''
  try {
    const catalogBook = { ...book.value, origin }
    const resp = await (forceRefresh
      ? API.refreshToc(catalogBook)
      : API.getChapterList(catalogBook))
    const { isSuccess, data, errorMsg } = resp.data
    if (isSuccess && Array.isArray(data)) {
      catalog.value = data
      const oldIdentity = store.catalogIdentity
      store.catalog = data
      store.catalogIdentity = `${book.value.bookUrl}\u0000${origin}`
      if (oldIdentity !== store.catalogIdentity) store.catalogGeneration++
    } else {
      toast.error(errorMsg || '加载目录失败')
    }
  } catch (e: any) {
    console.error('[BookInfo] 获取目录异常:', e)
    toast.error(e?.message || '加载目录失败')
  } finally {
    loadingCatalog.value = false
  }
}

// 刷新书籍详情与目录 (对照 Compose 端书籍详情页下拉刷新)
const refreshBookInfo = async () => {
  if (!book.value?.bookUrl || isRefreshing.value) return
  isRefreshing.value = true
  toast.info('正在刷新书籍与目录...')
  try {
    const origin = ('origin' in book.value ? book.value.origin : '') || ''
    // 1. 调用后端 refreshToc 接口: 重新回源抓取目录并落库更新书籍最新章节
    const resp = await API.refreshToc({ ...book.value, origin })
    const { isSuccess, data, errorMsg } = resp.data
    if (isSuccess && Array.isArray(data)) {
      catalog.value = data
      const oldIdentity = store.catalogIdentity
      store.catalog = data
      store.catalogIdentity = `${book.value.bookUrl}\u0000${origin}`
      if (oldIdentity !== store.catalogIdentity) store.catalogGeneration++

      // 2. 若在书架，重新同步最新的书架数据 (更新最新章节名称 latestChapterTitle、章节数等)
      if (inBookshelf.value) {
        await store.loadBookShelf(store.currentGroupId, true)
        const updated = store.shelf.find(b => b.bookUrl === book.value?.bookUrl)
        if (updated) {
          book.value = { ...book.value, ...updated }
          store.detailBook = book.value
        }
      } else if (data.length > 0) {
        // 在线临时书籍：根据最新目录末尾更新总章节数与最新章节
        const lastChap = data[data.length - 1]
        book.value = {
          ...book.value,
          totalChapterNum: data.length,
          latestChapterTitle: lastChap.title,
        }
      }
      toast.success('刷新成功')
    } else {
      toast.error(errorMsg || '刷新失败')
    }
  } catch (e: any) {
    console.error('[BookInfo] 刷新异常:', e)
    toast.error(e?.message || '刷新失败')
  } finally {
    isRefreshing.value = false
  }
}

// 来源信息提示
const showOriginInfo = () => {
  if (!book.value) return
  toast.info(`来源: ${originText.value}`)
}

// 搜索作者 (对照 Compose onSearchAuthor)
const searchAuthor = () => {
  if (!book.value?.author) return
  const rawAuthor = book.value.author.trim()
  const tmp = rawAuthor.split('::')
  const origin = ('origin' in book.value ? book.value.origin : '') || ''

  // 1. 如果包含 :: 且后半部分为探索 URL，跳转发现分类页
  if (tmp.length > 1 && tmp[1] && origin && isLegadoUrl(origin)) {
    router.push({
      path: '/explore-show',
      query: {
        sourceUrl: origin,
        sourceName: originText.value,
        exploreUrl: tmp[1],
        title: tmp[0],
      },
    })
    return
  }

  // 2. 普通作者搜索
  const cleanName = displayAuthor.value || tmp[0].trim()
  if (!cleanName) return

  const query: Record<string, string> = { key: cleanName }
  if (origin && isLegadoUrl(origin) && originText.value) {
    query.sourceUrl = origin
    query.sourceName = originText.value
  }
  router.push({ path: '/search', query })
}

// 搜索标签 (对照 Compose onSearchKind)
const searchTag = (tagItem: BookTagItem) => {
  const origin = (book.value && 'origin' in book.value ? book.value.origin : '') || ''
  if (tagItem.exploreUrl && origin && isLegadoUrl(origin)) {
    router.push({
      path: '/explore-show',
      query: {
        sourceUrl: origin,
        sourceName: originText.value,
        exploreUrl: tagItem.exploreUrl,
        title: tagItem.title,
      },
    })
    return
  }

  const query: Record<string, string> = { key: tagItem.title }
  if (origin && isLegadoUrl(origin) && originText.value) {
    query.sourceUrl = origin
    query.sourceName = originText.value
  }
  router.push({ path: '/search', query })
}

// 加入/移出书架
const toggleShelf = async () => {
  if (!book.value || isOperatingShelf.value) return
  isOperatingShelf.value = true
  try {
    if (inBookshelf.value) {
      const resp = await API.deleteBook(book.value as BaseBook)
      if (!resp.data.isSuccess) throw new Error(resp.data.errorMsg || '移出书架失败')
      toast.success('已从书架移出')
      store.loadBookShelf(store.currentGroupId, true)
      goBack()
    } else {
      const resp = await API.saveBook(book.value as BaseBook)
      if (!resp.data.isSuccess) throw new Error(resp.data.errorMsg || '放入书架失败')
      toast.success('已放入书架')
      await store.loadBookShelf(store.currentGroupId, true)
    }
  } catch (e) {
    toast.error('操作失败')
  } finally {
    isOperatingShelf.value = false
  }
}

// 选择某章开始阅读
const onSelectChapter = (idx: number) => {
  showCatalogDialog.value = false
  startReading(idx)
}

// 启动阅读器
const startReading = async (targetIndex?: number) => {
  if (!book.value) return

  // 自动将在线未入架的书放入书架，对齐 Legado 原生逻辑
  let nowInShelf = inBookshelf.value
  if (!nowInShelf) {
    try {
      const resp = await API.saveBook(book.value as BaseBook)
      if (!resp.data.isSuccess) throw new Error(resp.data.errorMsg || '放入书架失败')
      await store.loadBookShelf(store.currentGroupId, true)
      nowInShelf = true
    } catch (e) {
      nowInShelf = false
      console.warn('[BookInfo] 自动加入书架失败:', e)
    }
  }

  const index = targetIndex !== undefined ? targetIndex : durChapterIndex.value
  const pos = targetIndex !== undefined && targetIndex !== durChapterIndex.value ? 0 : ((book.value as Book).durChapterPos || 0)

  const origin = ('origin' in book.value ? book.value.origin : '') || ''
  const bookType = ('type' in book.value ? book.value.type : 0) || 0
  const cover = book.value.coverUrl || ''

  sessionStorage.setItem('bookUrl', book.value.bookUrl)
  sessionStorage.setItem('bookName', book.value.name)
  sessionStorage.setItem('bookAuthor', book.value.author)
  sessionStorage.setItem('chapterIndex', String(index))
  sessionStorage.setItem('chapterPos', String(pos))
  sessionStorage.setItem('isSeachBook', String(!nowInShelf))
  sessionStorage.setItem('bookOrigin', origin)
  sessionStorage.setItem('bookType', String(bookType))
  sessionStorage.setItem('bookCover', cover)

  store.readingBook = {
    ...book.value,
    chapterIndex: index,
    chapterPos: pos,
    isSeachBook: !nowInShelf,
    coverUrl: cover,
    origin,
  }

  router.push({
    path: '/chapter',
    query: { bookUrl: book.value.bookUrl },
  })
}

onMounted(() => {
  initBook()
  if (book.value) {
    loadCatalog()
  } else {
    toast.error('未找到书籍信息')
    goBack()
  }
})
</script>

<style lang="scss" scoped>
.book-info-page {
  position: relative;
  min-height: 100vh;
  background-color: var(--web-bg, #f6f8fa);
  color: var(--web-text, #2c3e50);
  padding-bottom: 90px;
  overflow-x: hidden;
  box-sizing: border-box;

  &.night {
    --web-bg: #141414;
    --web-bg-white: #1e1e1e;
    --web-text: #e0e0e0;
    --web-text-secondary: #888888;
    --web-border: #333333;
    --web-border-light: #282828;
    background-color: #141414;
    color: #e0e0e0;
  }
}

// 顶部导航栏
.info-top-bar {
  position: sticky;
  top: 0;
  left: 0;
  right: 0;
  height: 52px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 16px;
  background: rgba(255, 255, 255, 0.9);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  border-bottom: 1px solid rgba(0, 0, 0, 0.06);
  z-index: 100;

  .night & {
    background: rgba(30, 30, 30, 0.9);
    border-bottom-color: rgba(255, 255, 255, 0.08);
  }

  .top-btn {
    width: 36px;
    height: 36px;
    display: flex;
    align-items: center;
    justify-content: center;
    border: none;
    background: transparent;
    color: inherit;
    border-radius: 50%;
    cursor: pointer;
    transition: background 0.2s;

    &:hover {
      background: rgba(0, 0, 0, 0.05);
    }
    .night &:hover {
      background: rgba(255, 255, 255, 0.1);
    }
  }

  .top-title {
    font-size: 16px;
    font-weight: 600;
  }

  .top-placeholder {
    width: 36px;
    height: 36px;
  }
}

// 主体内容
.info-content {
  position: relative;
  max-width: 760px;
  margin: 0 auto;
  padding: 16px 16px 24px;
  z-index: 1;
}

// 头部封面 + 书籍基本信息 (无渐变阴影)
.book-header-card {
  display: flex;
  gap: 20px;
  padding: 16px;
  background: var(--web-bg-white, #ffffff);
  border-radius: 12px;
  border: 1px solid var(--web-border-light, #eef0f3);
  margin-bottom: 14px;
  box-shadow: none;

  .cover-wrapper {
    flex-shrink: 0;
    width: 110px;
    height: 146px;
    border-radius: 8px;
    overflow: hidden;
    border: 1px solid var(--web-border-light, #eef0f3);
    background: #e8ecf0;
    box-shadow: none;

    .book-cover {
      width: 100%;
      height: 100%;
      object-fit: cover;
      display: block;
    }
  }

  .book-main-info {
    flex: 1;
    display: flex;
    flex-direction: column;
    justify-content: center;
    min-width: 0;

    .book-name {
      margin: 0 0 6px;
      font-size: 20px;
      font-weight: 700;
      line-height: 1.35;
      color: var(--web-text, #1f2937);
      word-break: break-word;
    }

    .author-row {
      font-size: 14px;
      margin-bottom: 6px;
      cursor: pointer;
      display: inline-flex;
      align-items: center;

      .author-label {
        color: var(--web-text-secondary, #6b7280);
      }
      .author-name {
        color: var(--web-primary, #3b82f6);
        font-weight: 500;
        &:hover {
          text-decoration: underline;
        }
      }
    }

    .meta-row {
      display: flex;
      align-items: center;
      gap: 6px;
      font-size: 13px;
      color: var(--web-text-secondary, #6b7280);
      margin-bottom: 6px;
    }

    .latest-chapter-row {
      display: flex;
      align-items: center;
      gap: 5px;
      font-size: 13px;
      color: var(--web-text-secondary, #6b7280);
      margin-bottom: 6px;
      overflow: hidden;
      white-space: nowrap;
      text-overflow: ellipsis;

      .latest-icon {
        flex-shrink: 0;
        color: #f59e0b;
      }
      .latest-text {
        overflow: hidden;
        text-overflow: ellipsis;
      }
    }

    .read-progress-row {
      display: flex;
      align-items: center;
      gap: 6px;
      font-size: 13px;
      overflow: hidden;
      white-space: nowrap;
      text-overflow: ellipsis;

      .progress-badge {
        padding: 1px 6px;
        background: rgba(59, 130, 246, 0.12);
        color: var(--web-primary, #3b82f6);
        border-radius: 4px;
        font-size: 11px;
        font-weight: 600;
        flex-shrink: 0;
      }
      .progress-text {
        color: var(--web-text, #374151);
        overflow: hidden;
        text-overflow: ellipsis;
      }
    }
  }
}

// 动作行 (4 按钮网格)
.actions-row {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 8px;
  margin-bottom: 14px;

  .action-cell {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    padding: 12px 6px;
    background: var(--web-bg-white, #ffffff);
    border-radius: 10px;
    border: 1px solid var(--web-border-light, #eef0f3);
    cursor: pointer;
    user-select: none;
    transition: all 0.2s ease;

    &:hover {
      background: var(--web-primary-light, #ecf5ff);
      border-color: var(--web-primary, #3b82f6);
    }

    .action-icon-box {
      color: var(--web-primary, #3b82f6);
      margin-bottom: 4px;
      display: flex;
      align-items: center;
      justify-content: center;
    }

    .action-label {
      font-size: 13px;
      font-weight: 600;
      color: var(--web-text, #1f2937);
      max-width: 100%;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      margin-bottom: 2px;
      text-align: center;
    }

    .action-sub {
      font-size: 11px;
      color: var(--web-text-secondary, #9ca3af);
    }
  }
}

// 通用卡片容器
.section-card {
  background: var(--web-bg-white, #ffffff);
  border-radius: 12px;
  padding: 16px;
  border: 1px solid var(--web-border-light, #eef0f3);
  margin-bottom: 14px;
  box-shadow: none;

  .section-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 10px;

    .section-title {
      font-size: 15px;
      font-weight: 600;
      color: var(--web-text, #1f2937);
    }
  }
}

// 分类标签
.tag-flow {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;

  .tag-chip {
    padding: 4px 12px;
    border-radius: 16px;
    background: var(--web-bg, #f3f4f6);
    border: 1px solid var(--web-border-light, #e5e7eb);
    font-size: 12px;
    color: var(--web-text, #374151);
    cursor: pointer;
    transition: all 0.2s;

    &:hover {
      background: var(--web-primary-light, #ecf5ff);
      border-color: var(--web-primary, #3b82f6);
      color: var(--web-primary, #3b82f6);
    }
  }
}

// 简介：完全展示
.intro-body {
  font-size: 14px;
  line-height: 1.7;
  color: var(--web-text-secondary, #4b5563);
  white-space: pre-wrap;
  word-break: break-word;
}

// 底部悬浮双按钮栏
.info-bottom-bar {
  position: fixed;
  bottom: 0;
  left: 0;
  right: 0;
  display: flex;
  gap: 12px;
  padding: 12px 16px calc(12px + env(safe-area-inset-bottom, 0px));
  background: rgba(255, 255, 255, 0.9);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  border-top: 1px solid rgba(0, 0, 0, 0.06);
  z-index: 100;
  max-width: 760px;
  margin: 0 auto;

  .night & {
    background: rgba(25, 25, 25, 0.92);
    border-top-color: rgba(255, 255, 255, 0.08);
  }

  .shelf-btn,
  .read-btn {
    flex: 1;
    height: 44px;
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 8px;
    border-radius: 22px;
    font-size: 15px;
    font-weight: 600;
    cursor: pointer;
    transition: all 0.2s;
    user-select: none;
    border: none;
    outline: none;
  }

  .shelf-btn {
    background: var(--web-bg, #edf2f7);
    color: var(--web-text, #374151);

    &:hover {
      background: #e2e8f0;
    }

    &.in {
      color: #ef4444;
      background: rgba(239, 68, 68, 0.1);
      &:hover {
        background: rgba(239, 68, 68, 0.18);
      }
    }
  }

  .read-btn {
    background: var(--web-primary, #3b82f6);
    color: #ffffff;
    box-shadow: 0 4px 12px rgba(59, 130, 246, 0.35);

    &:hover {
      background: var(--web-primary-hover, #2563eb);
      box-shadow: 0 4px 16px rgba(59, 130, 246, 0.45);
    }
  }
}

// 移动端适配
@media (max-width: 640px) {
  .book-header-card {
    .cover-wrapper {
      width: 90px;
      height: 120px;
    }
    .book-main-info {
      .book-name {
        font-size: 17px;
      }
    }
  }

  .actions-row {
    .action-cell {
      padding: 10px 4px;
      .action-label {
        font-size: 12px;
      }
    }
  }
}

.spin-icon {
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  from {
    transform: rotate(0deg);
  }
  to {
    transform: rotate(360deg);
  }
}
</style>
