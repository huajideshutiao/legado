<template>
  <div :class="{ 'search-page': true, night: isNight, day: !isNight }">
    <!-- 顶栏 -->
    <header class="page-topbar">
      <button class="topbar-btn back-btn" type="button" aria-label="返回" @click="goBack">
        <svg viewBox="0 0 24 24" fill="currentColor">
          <path d="M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z" />
        </svg>
      </button>

      <div class="search-input-wrap">
        <svg class="search-icon" viewBox="0 0 24 24" fill="currentColor">
          <path
            d="M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3S3 5.91 3 9.5 5.91 16 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z"
          />
        </svg>
        <input
          ref="searchInputRef"
          class="search-input"
          v-model="keyword"
          type="text"
          placeholder="搜索书名、作者或关键词"
          @keyup.enter="handleSearch"
        />
        <button
          v-if="keyword"
          class="clear-btn"
          type="button"
          aria-label="清空"
          @click="clearKeyword"
        >
          <svg viewBox="0 0 24 24" fill="currentColor">
            <path
              d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z"
            />
          </svg>
        </button>
      </div>

      <button
        class="search-action-btn"
        :class="{ searching: isSearching }"
        type="button"
        @click="isSearching ? stopSearch() : handleSearch()"
      >
        {{ isSearching ? '停止' : '搜索' }}
      </button>
    </header>

    <!-- 搜索范围选择栏 (对齐 Compose SearchScope) -->
    <div class="search-scope-bar">
      <button class="scope-chip" type="button" @click="showScopeModal = true">
        <span class="scope-label">范围:</span>
        <span class="scope-value">{{ scopeDisplayText }}</span>
        <svg class="scope-arrow" viewBox="0 0 24 24" fill="currentColor">
          <path d="M7 10l5 5 5-5z" />
        </svg>
      </button>
      <button
        v-if="selectedSource || selectedGroup"
        class="scope-reset-btn"
        type="button"
        @click.stop="resetScope"
        title="恢复全网搜索"
      >
        重置为全网
      </button>
    </div>

    <!-- 搜索进度状态条 -->
    <div v-if="isSearching" class="search-statusbar">
      <div class="spinner"></div>
      <span>{{ searchingStatusText }}，已搜到 {{ results.length }} 本书...</span>
    </div>

    <!-- 主体区域 -->
    <div class="search-content" ref="contentRef">
      <!-- 搜索历史 (未开始搜索且输入为空时显示) -->
      <div v-if="!keyword.trim() && !hasSearched && !isSearching" class="history-panel">
        <div v-if="historyList.length > 0" class="section">
          <div class="section-header">
            <span class="section-title">搜索历史</span>
            <button class="text-btn" type="button" @click="clearHistory">清空</button>
          </div>
          <div class="tags-row">
            <span
              v-for="item in historyList"
              :key="item"
              class="history-tag"
              @click="quickSearch(item)"
            >
              {{ item }}
            </span>
          </div>
        </div>
        <div v-else class="empty-history-hint">
          输入书名、作者或关键词开始搜索
        </div>
      </div>

      <!-- 本地过滤书架书籍 (用户输入关键词但尚未回车发起全网搜索) -->
      <div v-else-if="!hasSearched && !isSearching" class="results-panel shelf-filter-panel">
        <div class="results-header">
          <span class="header-count">
            书架「{{ currentShelfGroupName }}」过滤结果 (共 {{ filteredShelfBooks.length }} 本)
          </span>
          <button class="filter-search-btn" type="button" @click="handleSearch">
            全网搜索
          </button>
        </div>

        <div v-if="filteredShelfBooks.length > 0" class="book-list">
          <div
            v-for="book in filteredShelfBooks"
            :key="book.bookUrl"
            class="book-card"
            @click="toBookInfo(book)"
          >
            <div class="book-cover-wrap">
              <img
                class="book-cover"
                :src="getCover(book)"
                @error.once="proxyImage"
                alt=""
                loading="lazy"
              />
            </div>
            <div class="book-info">
              <div class="book-title">{{ book.name }}</div>
              <div class="book-meta">
                <span class="book-author">{{ book.author }}</span>
                <span v-if="book.originName" class="source-tag">{{ book.originName }}</span>
              </div>
              <div v-if="book.kind" class="book-kinds">
                <span
                  v-for="k in book.kind.split(/[,，\s]/).filter(Boolean).slice(0, 3)"
                  :key="k"
                  class="kind-chip"
                >
                  {{ k }}
                </span>
              </div>
              <div v-if="book.durChapterTitle" class="book-dur">
                已读：{{ book.durChapterTitle }}
              </div>
              <div class="book-latest">最新：{{ book.latestChapterTitle || '暂无更新章节' }}</div>
            </div>
          </div>
        </div>

        <div v-else class="empty-state">
          当前分组书架未匹配到书籍，按回车或点击“全网搜索”发起全网搜索
        </div>
      </div>

      <!-- 搜索结果列表 -->
      <div v-else class="results-panel">
        <div class="results-header" v-if="results.length > 0">
          <span class="header-count">共检索到 {{ results.length }} 本相关书籍</span>
        </div>

        <div class="book-list">
          <div
            v-for="book in results"
            :key="book.bookUrl"
            class="book-card"
            @click="toBookInfo(book)"
          >
            <div class="book-cover-wrap">
              <img
                class="book-cover"
                :src="getCover(book)"
                @error.once="proxyImage"
                alt=""
                loading="lazy"
              />
            </div>
            <div class="book-info">
              <div class="book-title">{{ book.name }}</div>
              <div class="book-meta">
                <span class="book-author">{{ book.author }}</span>
                <span v-if="book.originName" class="source-tag">{{ book.originName }}</span>
              </div>
              <div v-if="book.kind" class="book-kinds">
                <span
                  v-for="k in book.kind.split(/[,，\s]/).filter(Boolean).slice(0, 3)"
                  :key="k"
                  class="kind-chip"
                >
                  {{ k }}
                </span>
              </div>
              <div v-if="book.intro" class="book-intro">
                {{ book.intro }}
              </div>
              <div class="book-latest">最新：{{ book.latestChapterTitle || '暂无更新章节' }}</div>
            </div>
          </div>
        </div>

        <div v-if="!isSearching && results.length === 0" class="empty-state">
          未检索到相关书籍，请换个关键词试试
        </div>
      </div>
    </div>

    <!-- 搜索范围选择弹窗 (对照 Compose SearchScopeDialog) -->
    <div v-if="showScopeModal" class="scope-modal-mask" @click="showScopeModal = false">
      <div class="scope-modal" @click.stop>
        <div class="scope-modal-header">
          <div class="modal-tabs">
            <button
              class="tab-btn"
              :class="{ active: scopeTab === 'source' }"
              type="button"
              @click="scopeTab = 'source'"
            >
              按书源 ({{ scopeSources.length }})
            </button>
            <button
              class="tab-btn"
              :class="{ active: scopeTab === 'group' }"
              type="button"
              @click="scopeTab = 'group'"
            >
              按分组 ({{ scopeGroups.length }})
            </button>
          </div>
          <button class="modal-close" type="button" @click="showScopeModal = false">✕</button>
        </div>

        <!-- 按书源选择 -->
        <div v-if="scopeTab === 'source'" class="scope-tab-content">
          <div class="scope-filter-input-wrap">
            <input
              v-model="sourceFilterText"
              class="scope-filter-input"
              type="text"
              placeholder="快速过滤书源名称或分组..."
            />
          </div>
          <div class="scope-list">
            <div
              v-for="s in filteredScopeSources"
              :key="s.bookSourceUrl"
              class="scope-item"
              :class="{ selected: selectedSource?.bookSourceUrl === s.bookSourceUrl }"
              @click="selectSource(s)"
            >
              <div class="item-main">
                <span class="item-name">{{ s.bookSourceName }}</span>
                <span v-if="s.bookSourceGroup" class="item-group">{{ s.bookSourceGroup }}</span>
              </div>
              <span v-if="s.enabled === false" class="item-disabled-tag">未启用</span>
            </div>
          </div>
        </div>

        <!-- 按分组选择 -->
        <div v-else class="scope-tab-content">
          <div class="scope-list">
            <div
              v-for="g in scopeGroups"
              :key="g"
              class="scope-item"
              :class="{ selected: selectedGroup === g }"
              @click="selectGroup(g)"
            >
              <span class="item-name">{{ g }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { useBookStore } from '@/store'
import API from '@api'
import type { SeachBook, BaseBook, Book } from '@/book'
import type { WebBookSourcePart } from '@api'
import { isLegadoUrl } from '@/utils/utils'
import { toast } from '@/utils/toast'

const router = useRouter()
const route = useRoute()
const store = useBookStore()
const isNight = computed(() => store.isNight)

const keyword = ref('')
const searchInputRef = ref<HTMLInputElement>()
const isSearching = ref(false)
const hasSearched = ref(false)
const results = shallowRef<SeachBook[]>([])

// 当前书架分组定位 (进入搜索前所在的书架分组)
const targetShelfGroupId = computed(() => {
  const q = route.query.shelfGroupId as string | undefined
  if (q !== undefined) return q
  return store.currentGroupId !== undefined ? String(store.currentGroupId) : undefined
})

const currentShelfBooks = computed<Book[]>(() => {
  const gid = targetShelfGroupId.value
  if (gid !== undefined && Object.prototype.hasOwnProperty.call(store.shelfGroupCache, gid)) {
    return store.shelfGroupCache[gid]
  }
  return store.shelf
})

const currentShelfGroupName = computed(() => {
  const gid = targetShelfGroupId.value
  if (gid !== undefined) {
    const found = store.groups.find(g => String(g.groupId) === String(gid))
    if (found) return found.groupName
  }
  return '当前分组'
})

const filteredShelfBooks = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return []
  return currentShelfBooks.value.filter(book => {
    const nameMatch = book.name?.toLowerCase().includes(kw)
    const authorMatch = book.author?.toLowerCase().includes(kw)
    return Boolean(nameMatch || authorMatch)
  })
})

// 选源范围状态 (对照 Compose SearchScope)
const scopeSources = ref<WebBookSourcePart[]>([])
const selectedSource = ref<WebBookSourcePart | null>(null)
const selectedGroup = ref<string | null>(null)
const showScopeModal = ref(false)
const scopeTab = ref<'source' | 'group'>('source')
const sourceFilterText = ref('')

const SCOPE_STORAGE_KEY = 'legado_web_search_scope'

interface SavedScope {
  type: 'all' | 'source' | 'group'
  sourceUrl?: string
  sourceName?: string
  groupName?: string
}

const saveScope = (scope: SavedScope) => {
  try {
    localStorage.setItem(SCOPE_STORAGE_KEY, JSON.stringify(scope))
  } catch (e) {
    console.error('保存搜索范围失败', e)
  }
}

const loadSavedScope = (): SavedScope | null => {
  try {
    const raw = localStorage.getItem(SCOPE_STORAGE_KEY)
    if (!raw) return null
    return JSON.parse(raw) as SavedScope
  } catch {
    return null
  }
}

const loadScopeSources = async () => {
  try {
    const resp = await API.getBookSourcesPart()
    if (resp.data.isSuccess && Array.isArray(resp.data.data)) {
      scopeSources.value = resp.data.data

      // 1. 如果路由带了 sourceUrl，优先使用路由传参并持久化
      const targetUrl = route.query.sourceUrl as string
      if (targetUrl) {
        const found = scopeSources.value.find(s => s.bookSourceUrl === targetUrl)
        selectedSource.value = found || {
          bookSourceUrl: targetUrl,
          bookSourceName: (route.query.sourceName as string) || targetUrl,
          customOrder: 0,
          enabled: true,
          enabledExplore: true,
          hasLoginUrl: false,
          lastUpdateTime: 0,
          respondTime: 0,
          weight: 0,
          hasExploreUrl: false,
        }
        selectedGroup.value = null
        saveScope({
          type: 'source',
          sourceUrl: selectedSource.value.bookSourceUrl,
          sourceName: selectedSource.value.bookSourceName,
        })
        return
      }

      // 2. 否则读取并恢复此前记住的搜索范围
      const saved = loadSavedScope()
      if (saved) {
        if (saved.type === 'source' && saved.sourceUrl) {
          const found = scopeSources.value.find(s => s.bookSourceUrl === saved.sourceUrl)
          selectedSource.value = found || {
            bookSourceUrl: saved.sourceUrl,
            bookSourceName: saved.sourceName || saved.sourceUrl,
            customOrder: 0,
            enabled: true,
            enabledExplore: true,
            hasLoginUrl: false,
            lastUpdateTime: 0,
            respondTime: 0,
            weight: 0,
            hasExploreUrl: false,
          }
          selectedGroup.value = null
        } else if (saved.type === 'group' && saved.groupName) {
          selectedGroup.value = saved.groupName
          selectedSource.value = null
        } else if (saved.type === 'all') {
          selectedSource.value = null
          selectedGroup.value = null
        }
      }
    }
  } catch (e) {
    console.error('加载书源列表失败', e)
  }
}

const scopeGroups = computed(() => {
  const set = new Set<string>()
  for (const s of scopeSources.value) {
    if (s.bookSourceGroup) {
      for (const g of s.bookSourceGroup.split(/[,，]/)) {
        const trimmed = g.trim()
        if (trimmed) set.add(trimmed)
      }
    }
  }
  return Array.from(set).sort()
})

const filteredScopeSources = computed(() => {
  const key = sourceFilterText.value.trim().toLowerCase()
  if (!key) return scopeSources.value
  return scopeSources.value.filter(
    s =>
      s.bookSourceName.toLowerCase().includes(key) ||
      (s.bookSourceGroup || '').toLowerCase().includes(key),
  )
})

const scopeDisplayText = computed(() => {
  if (selectedSource.value) {
    return `源: ${selectedSource.value.bookSourceName}`
  }
  if (selectedGroup.value) {
    return `分组: ${selectedGroup.value}`
  }
  return '全部书源'
})

const searchingStatusText = computed(() => {
  if (selectedSource.value) {
    return `正在【${selectedSource.value.bookSourceName}】中搜索`
  }
  if (selectedGroup.value) {
    return `正在【${selectedGroup.value}】分组中搜索`
  }
  return '全网聚合搜索中'
})

const selectSource = (source: WebBookSourcePart) => {
  selectedSource.value = source
  selectedGroup.value = null
  showScopeModal.value = false
  saveScope({ type: 'source', sourceUrl: source.bookSourceUrl, sourceName: source.bookSourceName })
  if (keyword.value.trim()) {
    handleSearch()
  }
}

const selectGroup = (group: string) => {
  selectedGroup.value = group
  selectedSource.value = null
  showScopeModal.value = false
  saveScope({ type: 'group', groupName: group })
  if (keyword.value.trim()) {
    handleSearch()
  }
}

const resetScope = () => {
  selectedSource.value = null
  selectedGroup.value = null
  saveScope({ type: 'all' })
  if (keyword.value.trim()) {
    handleSearch()
  }
}

const HISTORY_KEY = 'legado_web_search_history'

const historyList = ref<string[]>([])

const loadHistory = () => {
  try {
    const raw = localStorage.getItem(HISTORY_KEY)
    historyList.value = raw ? JSON.parse(raw) : []
  } catch {
    historyList.value = []
  }
}

const saveHistory = (word: string) => {
  const w = word.trim()
  if (!w) return
  const list = historyList.value.filter(item => item !== w)
  list.unshift(w)
  if (list.length > 20) list.pop()
  historyList.value = list
  localStorage.setItem(HISTORY_KEY, JSON.stringify(list))
}

const clearHistory = () => {
  historyList.value = []
  localStorage.removeItem(HISTORY_KEY)
}

const clearKeyword = () => {
  programmaticKeyword = null
  keyword.value = ''
  hasSearched.value = false
  stopSearch()
  results.value = []
  searchInputRef.value?.focus()
}

const goBack = () => {
  if (window.history.length > 1) {
    router.back()
  } else {
    router.push('/shelf')
  }
}

let activeSocket: WebSocket | null = null
let searchGeneration = 0
let programmaticKeyword: string | null = null

const assignKeywordForSearch = (word: string) => {
  programmaticKeyword = word
  keyword.value = word
}

const stopSearch = () => {
  searchGeneration++
  isSearching.value = false
  const socket = activeSocket
  activeSocket = null
  if (socket) {
    try {
      socket.close()
    } catch {}
  }
}

const handleSearch = () => {
  const word = keyword.value.trim()
  if (!word) return
  saveHistory(word)
  stopSearch()
  const generation = searchGeneration

  results.value = []
  hasSearched.value = true
  isSearching.value = true

  const tempResults: SeachBook[] = []
  const seenUrls = new Set<string>()

  let currentScope = 'all'
  if (selectedSource.value) {
    currentScope = `${selectedSource.value.bookSourceName.replace(':', '')}::${selectedSource.value.bookSourceUrl}`
  } else if (selectedGroup.value) {
    currentScope = selectedGroup.value
  }

  const socket = API.search(
    word,
    data => {
      if (generation !== searchGeneration || activeSocket !== socket) return
      if (Array.isArray(data)) {
        for (const b of data) {
          if (!seenUrls.has(b.bookUrl)) {
            seenUrls.add(b.bookUrl)
            tempResults.push(b)
          }
        }
        results.value = [...tempResults]
      }
    },
    () => {
      if (generation !== searchGeneration || activeSocket !== socket) return
      isSearching.value = false
      activeSocket = null
    },
    currentScope,
  )
  activeSocket = socket
}

const quickSearch = (word: string) => {
  assignKeywordForSearch(word)
  handleSearch()
}

const getCover = ({ bookUrl, coverUrl }: { bookUrl: string; coverUrl?: string }) => {
  if (!coverUrl) return API.getProxyCoverUrl(bookUrl)
  return isLegadoUrl(coverUrl) ? API.getProxyCoverUrl(coverUrl) : coverUrl
}

const proxyImage = (evt: Event) => {
  const target = evt.target as HTMLImageElement
  target.src = API.getProxyCoverUrl(target.src)
}

const toBookInfo = (book: SeachBook | Book) => {
  store.setDetailBook(book)
  router.push({
    path: '/book-info',
    query: { bookUrl: book.bookUrl },
  })
}

onMounted(async () => {
  loadHistory()

  // 1. 保证书架分组与书架缓存就绪，以支持本地实时过滤
  if (store.groups.length === 0) {
    await store.loadGroups()
  }
  if (currentShelfBooks.value.length === 0) {
    const gid = targetShelfGroupId.value ?? store.currentGroupId
    await store.loadBookShelf(gid)
  }

  // 1. 如果路由传了 sourceUrl，先同步初始化 selectedSource，保证范围立即就绪
  const targetUrl = route.query.sourceUrl as string
  const targetName = (route.query.sourceName as string) || targetUrl
  if (targetUrl) {
    selectedSource.value = {
      bookSourceUrl: targetUrl,
      bookSourceName: targetName,
      customOrder: 0,
      enabled: true,
      enabledExplore: true,
      hasLoginUrl: false,
      lastUpdateTime: 0,
      respondTime: 0,
      weight: 0,
      hasExploreUrl: false,
    }
    selectedGroup.value = null
  }

  // 2. 异步加载书源列表补充完整信息
  await loadScopeSources()

  // 3. 检查是否有搜索词，立即发起搜索
  const q = route.query.key as string
  if (q) {
    assignKeywordForSearch(q)
    handleSearch()
  } else {
    nextTick(() => {
      searchInputRef.value?.focus()
    })
  }
})

// 监听输入词变化，输入变化且未按下回车时切回本地分组过滤
watch(keyword, (newVal, oldVal) => {
  if (newVal === oldVal) return
  if (programmaticKeyword === newVal) {
    programmaticKeyword = null
    return
  }
  programmaticKeyword = null
  hasSearched.value = false
  if (isSearching.value) stopSearch()
})

// 监听路由参数变化 (从详情页或外部跳转时自动更新并触发搜索)
watch(
  () => [route.query.key, route.query.sourceUrl] as const,
  ([newKey, newSourceUrl]) => {
    if (newKey && typeof newKey === 'string') {
      assignKeywordForSearch(newKey)
      if (newSourceUrl && typeof newSourceUrl === 'string') {
        const targetName = (route.query.sourceName as string) || newSourceUrl
        const found = scopeSources.value.find(s => s.bookSourceUrl === newSourceUrl)
        selectedSource.value = found || {
          bookSourceUrl: newSourceUrl,
          bookSourceName: targetName,
          customOrder: 0,
          enabled: true,
          enabledExplore: true,
          hasLoginUrl: false,
          lastUpdateTime: 0,
          respondTime: 0,
          weight: 0,
          hasExploreUrl: false,
        }
        selectedGroup.value = null
      }
      handleSearch()
    }
  },
)

onUnmounted(() => {
  stopSearch()
})
</script>

<style lang="scss" scoped>
.search-page {
  height: 100vh;
  width: 100vw;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background-color: #f7f7f7;
  color: var(--web-text, #333);

  .page-topbar {
    flex: none;
    height: 52px;
    display: flex;
    align-items: center;
    padding: 0 12px;
    gap: 10px;
    background: #fff;
    border-bottom: 1px solid rgba(128, 128, 128, 0.15);

    .topbar-btn {
      width: 36px;
      height: 36px;
      display: flex;
      align-items: center;
      justify-content: center;
      border: none;
      background: transparent;
      cursor: pointer;
      color: #666;
      padding: 0;

      svg {
        width: 22px;
        height: 22px;
      }
    }

    .search-input-wrap {
      flex: 1;
      height: 36px;
      display: flex;
      align-items: center;
      background: #f0f2f5;
      border-radius: 18px;
      padding: 0 12px;
      gap: 8px;

      .search-icon {
        width: 18px;
        height: 18px;
        color: #999;
        flex-shrink: 0;
      }

      .search-input {
        flex: 1;
        min-width: 0;
        border: none;
        outline: none;
        background: transparent;
        font-size: 14px;
        color: inherit;

        &::placeholder {
          color: #aaa;
        }
      }

      .clear-btn {
        width: 20px;
        height: 20px;
        padding: 0;
        border: none;
        background: transparent;
        cursor: pointer;
        color: #999;
        display: flex;
        align-items: center;
        justify-content: center;

        svg {
          width: 16px;
          height: 16px;
        }
      }
    }

    .search-action-btn {
      flex-shrink: 0;
      border: none;
      background: var(--web-primary, #1e80ff);
      color: #fff;
      font-size: 14px;
      font-weight: 500;
      padding: 6px 14px;
      border-radius: 16px;
      cursor: pointer;
      transition: all 0.2s;

      &.searching {
        background: #ff4d4f;
      }
    }
  }

  .search-scope-bar {
    flex: none;
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 6px 14px;
    background: #fff;
    border-bottom: 1px solid #f0f0f0;
    font-size: 12px;

    .scope-chip {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      padding: 3px 10px;
      background: #f1f5f9;
      border: 1px solid #e2e8f0;
      border-radius: 12px;
      color: #334155;
      cursor: pointer;
      font-size: 12px;
      transition: all 0.15s;

      &:hover {
        border-color: var(--web-primary, #1e80ff);
        color: var(--web-primary, #1e80ff);
        background: #eff6ff;
      }

      .scope-label {
        color: #64748b;
      }

      .scope-value {
        font-weight: 500;
        max-width: 220px;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }

      .scope-arrow {
        width: 14px;
        height: 14px;
        color: #94a3b8;
      }
    }

    .scope-reset-btn {
      border: none;
      background: transparent;
      color: #ef4444;
      cursor: pointer;
      font-size: 11px;
      padding: 2px 6px;
      border-radius: 4px;

      &:hover {
        background: rgba(239, 68, 68, 0.08);
      }
    }
  }

  .search-statusbar {
    flex: none;
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 6px 16px;
    background: rgba(30, 128, 255, 0.08);
    font-size: 12px;
    color: var(--web-primary, #1e80ff);

    .spinner {
      width: 12px;
      height: 12px;
      border: 2px solid var(--web-primary, #1e80ff);
      border-top-color: transparent;
      border-radius: 50%;
      animation: spin 0.8s linear infinite;
    }
  }

  .search-content {
    flex: 1;
    overflow-y: auto;
    padding: 12px 14px 40px;
  }

  .history-panel {
    max-width: 800px;
    margin: 0 auto;

    .section {
      margin-bottom: 24px;

      .section-header {
        display: flex;
        align-items: center;
        justify-content: space-between;
        margin-bottom: 12px;

        .section-title {
          font-size: 14px;
          font-weight: 600;
          color: #555;
        }

        .text-btn {
          border: none;
          background: transparent;
          color: #999;
          font-size: 12px;
          cursor: pointer;

          &:hover {
            color: #666;
          }
        }
      }

      .tags-row {
        display: flex;
        flex-wrap: wrap;
        gap: 8px;

        .history-tag,
        .hot-tag {
          padding: 6px 12px;
          border-radius: 16px;
          font-size: 13px;
          cursor: pointer;
          user-select: none;
          transition: all 0.15s;
        }

        .history-tag {
          background: #eef0f3;
          color: #444;

          &:hover {
            background: #e2e5e9;
          }
        }
      }
    }

    .empty-history-hint {
      text-align: center;
      padding: 40px 0;
      font-size: 13px;
      color: #999;
    }
  }

  .results-panel {
    max-width: 900px;
    margin: 0 auto;

    .results-header {
      display: flex;
      justify-content: space-between;
      align-items: baseline;
      padding: 0 4px 10px;
      font-size: 13px;
      color: #888;

      .filter-search-btn {
        border: none;
        background: transparent;
        color: var(--web-primary, #1e80ff);
        font-size: 12px;
        cursor: pointer;
        padding: 2px 6px;
        border-radius: 4px;

        &:hover {
          background: rgba(30, 128, 255, 0.08);
        }
      }
    }

    .book-list {
      display: flex;
      flex-direction: column;
      gap: 10px;

      .book-card {
        display: flex;
        padding: 12px;
        background: #fff;
        border-radius: 8px;
        cursor: pointer;
        transition: transform 0.15s, box-shadow 0.15s;
        box-shadow: 0 1px 3px rgba(0, 0, 0, 0.04);

        &:hover {
          box-shadow: 0 4px 12px rgba(0, 0, 0, 0.08);
        }

        .book-cover-wrap {
          width: 72px;
          height: 98px;
          flex-shrink: 0;
          margin-right: 14px;

          .book-cover {
            width: 72px;
            height: 98px;
            object-fit: cover;
            border-radius: 4px;
          }
        }

        .book-info {
          flex: 1;
          min-width: 0;
          display: flex;
          flex-direction: column;
          justify-content: space-between;

          .book-title {
            font-size: 15px;
            font-weight: 700;
            color: #222;
            white-space: nowrap;
            overflow: hidden;
            text-overflow: ellipsis;
          }

          .book-meta {
            display: flex;
            align-items: center;
            gap: 8px;
            font-size: 12px;
            color: #666;
            margin-top: 2px;

            .source-tag {
              background: #f0f0f0;
              padding: 2px 6px;
              border-radius: 4px;
              font-size: 11px;
              color: #888;
            }
          }

          .book-kinds {
            display: flex;
            gap: 6px;
            margin-top: 4px;

            .kind-chip {
              background: #eef2ff;
              color: #4f46e5;
              font-size: 11px;
              padding: 1px 6px;
              border-radius: 3px;
            }
          }

          .book-intro {
            font-size: 12px;
            color: #888;
            line-height: 1.4;
            display: -webkit-box;
            -webkit-line-clamp: 2;
            line-clamp: 2;
            -webkit-box-orient: vertical;
            overflow: hidden;
            margin-top: 4px;
          }

          .book-latest {
            font-size: 12px;
            color: #999;
            margin-top: 4px;
            white-space: nowrap;
            overflow: hidden;
            text-overflow: ellipsis;
          }

          .book-dur {
            font-size: 12px;
            color: var(--web-primary, #1e80ff);
            margin-top: 4px;
            white-space: nowrap;
            overflow: hidden;
            text-overflow: ellipsis;
          }
        }
      }
    }

    .empty-state {
      text-align: center;
      padding: 60px 0;
      font-size: 14px;
      color: #999;
    }
  }

  .scope-modal-mask {
    position: fixed;
    inset: 0;
    background: rgba(0, 0, 0, 0.45);
    display: flex;
    align-items: center;
    justify-content: center;
    z-index: 1000;
    padding: 16px;

    .scope-modal {
      width: 100%;
      max-width: 460px;
      max-height: 80vh;
      background: #fff;
      border-radius: 12px;
      display: flex;
      flex-direction: column;
      overflow: hidden;
      box-shadow: 0 10px 25px rgba(0, 0, 0, 0.15);

      .scope-modal-header {
        display: flex;
        align-items: center;
        justify-content: space-between;
        padding: 12px 16px;
        border-bottom: 1px solid #f0f0f0;

        .modal-tabs {
          display: flex;
          gap: 8px;

          .tab-btn {
            border: none;
            background: transparent;
            font-size: 14px;
            font-weight: 500;
            color: #64748b;
            padding: 4px 10px;
            border-radius: 6px;
            cursor: pointer;

            &.active {
              background: #eff6ff;
              color: var(--web-primary, #1e80ff);
            }
          }
        }

        .modal-close {
          border: none;
          background: transparent;
          font-size: 16px;
          color: #94a3b8;
          cursor: pointer;
          padding: 4px;
        }
      }

      .scope-tab-content {
        display: flex;
        flex-direction: column;
        overflow: hidden;
        flex: 1;

        .scope-filter-input-wrap {
          padding: 10px 16px 6px;

          .scope-filter-input {
            width: 100%;
            height: 34px;
            padding: 0 10px;
            border: 1px solid #e2e8f0;
            border-radius: 6px;
            outline: none;
            font-size: 13px;
            box-sizing: border-box;

            &:focus {
              border-color: var(--web-primary, #1e80ff);
            }
          }
        }

        .scope-list {
          flex: 1;
          overflow-y: auto;
          padding: 4px 12px 12px;

          .scope-item {
            display: flex;
            align-items: center;
            justify-content: space-between;
            padding: 8px 10px;
            border-radius: 6px;
            cursor: pointer;
            font-size: 13px;
            transition: background 0.15s;

            &:hover {
              background: #f8fafc;
            }

            &.selected {
              background: #eff6ff;
              color: var(--web-primary, #1e80ff);
              font-weight: 500;
            }

            .item-main {
              display: flex;
              align-items: center;
              gap: 8px;
              overflow: hidden;

              .item-name {
                overflow: hidden;
                text-overflow: ellipsis;
                white-space: nowrap;
              }

              .item-group {
                font-size: 11px;
                padding: 1px 6px;
                border-radius: 3px;
                background: #f1f5f9;
                color: #64748b;
                flex-shrink: 0;
              }
            }

            .item-disabled-tag {
              font-size: 11px;
              padding: 1px 6px;
              border-radius: 3px;
              background: #f1f5f9;
              color: #94a3b8;
              flex-shrink: 0;
            }
          }
        }
      }
    }
  }
}

.night {
  background-color: #161819;
  color: #aeaeae;

  .page-topbar {
    background: #242526;
    border-bottom-color: rgba(255, 255, 255, 0.08);

    .topbar-btn {
      color: #aaa;
    }

    .search-input-wrap {
      background: #333;

      .search-icon,
      .clear-btn {
        color: #888;
      }
    }
  }

  .history-panel .section {
    .section-header .section-title {
      color: #999;
    }

    .tags-row .history-tag {
      background: #333;
      color: #ccc;

      &:hover {
        background: #3e3e3e;
      }
    }
  }

  .results-panel .book-list .book-card {
    background: #242526;
    box-shadow: none;

    &:hover {
      box-shadow: 0 4px 12px rgba(0, 0, 0, 0.3);
    }

    .book-title {
      color: #ddd !important;
    }

    .book-meta {
      color: #888;

      .source-tag {
        background: #333;
        color: #aaa;
      }
    }

    .book-kinds .kind-chip {
      background: #283046;
      color: #818cf8;
    }

    .book-intro,
    .book-latest {
      color: #777;
    }

    .book-dur {
      color: #60a5fa !important;
    }
  }

  .search-scope-bar {
    background: #1e2022;
    border-bottom-color: rgba(255, 255, 255, 0.08);

    .scope-chip {
      background: #2b2c2e;
      border-color: #3a3b3d;
      color: #ddd;

      .scope-label { color: #888; }
      &:hover {
        background: #1e293b;
        border-color: #60a5fa;
        color: #60a5fa;
      }
    }
  }

  .scope-modal-mask {
    background: rgba(0, 0, 0, 0.7);

    .scope-modal {
      background: #242526;
      color: #ddd;

      .scope-modal-header {
        border-bottom-color: #333;

        .modal-tabs .tab-btn {
          color: #aaa;
          &.active {
            background: #1e293b;
            color: #60a5fa;
          }
        }

        .modal-close {
          color: #888;
        }
      }

      .scope-tab-content {
        .scope-filter-input-wrap .scope-filter-input {
          background: #18191a;
          border-color: #3a3b3d;
          color: #eee;

          &:focus {
            border-color: #60a5fa;
          }
        }

        .scope-list .scope-item {
          color: #ccc;
          &:hover {
            background: #2b2c2e;
          }
          &.selected {
            background: #1e293b;
            color: #60a5fa;
          }
          .item-main .item-group {
            background: #333;
            color: #aaa;
          }
          .item-disabled-tag {
            background: #2b2c2e;
            color: #777;
          }
        }
      }
    }
  }
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
