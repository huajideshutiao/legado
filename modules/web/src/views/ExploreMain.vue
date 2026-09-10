<template>
  <div :class="{ 'explore-wrapper': true, night: isNight, day: !isNight }">
    <!-- 顶栏：搜索框 (对照 Compose ExploreTitleBar) -->
    <header class="page-topbar">
      <div class="search-field">
        <svg class="search-icon" viewBox="0 0 24 24" fill="currentColor">
          <path
            d="M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3S3 5.91 3 9.5 5.91 16 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z"
          />
        </svg>
        <input
          v-model="searchKey"
          class="search-input"
          type="text"
          placeholder="搜索发现书源"
        />
        <button v-if="searchKey" class="clear-btn" type="button" @click="searchKey = ''">
          <svg viewBox="0 0 24 24" fill="currentColor">
            <path
              d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z"
            />
          </svg>
        </button>
      </div>
    </header>

    <!-- 发现书源列表 (对照 Compose LazyColumn) -->
    <div class="explore-scroll" ref="panelRef" @scroll.passive="onScroll">
      <div class="source-list" v-if="filteredSources.length > 0">
        <div
          v-for="item in filteredSources"
          :key="item.bookSourceUrl"
          class="source-item"
        >
          <!-- 书源条目行 (对照 Compose ExploreSourceItem 头部) -->
          <div class="item-header" @click="toggleExpand(item)">
            <span class="source-name">{{ item.bookSourceName }}</span>
            <div class="header-status">
              <!-- 展开且加载中时显示旋转菊花 (对照 Compose CircularProgressIndicator) -->
              <div
                v-if="expandedUrl === item.bookSourceUrl && expandedLoading.has(item.bookSourceUrl)"
                class="spinner"
              ></div>
              <!-- 展开箭头：收起时指向右，展开时旋转 90° 向下 (对照 Compose arrowRotation) -->
              <svg
                class="arrow-icon"
                :class="{ rotated: expandedUrl === item.bookSourceUrl }"
                viewBox="0 0 24 24"
                fill="currentColor"
              >
                <path d="M8.59 16.59L13.17 12 8.59 7.41 10 6l6 6-6 6-1.41-1.41z" />
              </svg>
            </div>
          </div>

          <!-- 分类展开区 (对照 Compose AnimatedVisibility + KindFlow) -->
          <div
            v-if="expandedUrl === item.bookSourceUrl"
            class="item-body"
          >
            <div
              v-if="expandedKinds.has(item.bookSourceUrl)"
              class="kinds-container"
            >
              <div
                v-if="expandedKinds.get(item.bookSourceUrl)!.length > 0"
                class="kinds-layout"
              >
                <button
                  v-for="(kind, idx) in expandedKinds.get(item.bookSourceUrl)"
                  :key="idx"
                  type="button"
                  class="kind-btn"
                  :class="[getColClass(kind), { 'is-title': kind.type === 'title', 'no-url': !kind.url }]"
                  @click="onOpenExplore(item, kind)"
                >
                  {{ kind.title }}
                </button>
              </div>
              <div v-else class="empty-kinds">
                该书源暂未解析到有效分类规则
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- 空状态 (对照 Compose explore_empty) -->
      <div class="empty" v-else-if="!isLoading">
        {{ searchKey ? '无匹配书源' : '暂无支持发现的书源' }}
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
defineOptions({ name: 'ExploreMain' })

import '@/assets/webui.css'
import { useLoading } from '@/hooks/loading'
import { useBookStore } from '@/store'
import API from '@api'
import type { BookSource } from '@/source'
import { toast } from '@/utils/toast'
import type { WebExploreKind, WebBookSourcePart } from '@api'

type ExploreKind = WebExploreKind
type BookSourcePart = WebBookSourcePart

const router = useRouter()
const store = useBookStore()
const isNight = computed(() => store.isNight)

const KINDS_STORAGE_KEY = 'legado_web_explore_kinds_cache'
const SOURCES_STORAGE_KEY = 'legado_web_explore_sources_cache'
const EXPANDED_URL_KEY = 'legado_web_explore_expanded_url'

// 会话持久化辅助方法
function initKindsFromStorage(): Map<string, ExploreKind[]> {
  const map = new Map<string, ExploreKind[]>()
  try {
    const raw = sessionStorage.getItem(KINDS_STORAGE_KEY)
    if (raw) {
      const obj = JSON.parse(raw) as Record<string, ExploreKind[]>
      for (const [k, v] of Object.entries(obj)) {
        if (Array.isArray(v)) {
          map.set(k, v)
        }
      }
    }
  } catch (e) {
    console.warn('[Explore] 读取分类缓存失败:', e)
  }
  return map
}

function initSourcesFromStorage(): BookSourcePart[] {
  try {
    const raw = sessionStorage.getItem(SOURCES_STORAGE_KEY)
    if (raw) {
      const list = JSON.parse(raw)
      if (Array.isArray(list)) return list
    }
  } catch (e) {
    console.warn('[Explore] 读取书源缓存失败:', e)
  }
  return []
}

function saveKindsCacheToStorage(map: Map<string, ExploreKind[]>) {
  try {
    const obj: Record<string, ExploreKind[]> = {}
    for (const [k, v] of map.entries()) {
      obj[k] = v
    }
    sessionStorage.setItem(KINDS_STORAGE_KEY, JSON.stringify(obj))
  } catch (e) {
    console.warn('[Explore] 写入分类缓存失败:', e)
  }
}

// 模块级单例缓存：在单页应用会话期间永不丢失，哪怕组件被销毁又重建也能瞬间复原
let globalSourcesCache: BookSourcePart[] = initSourcesFromStorage()
const globalKindsCache: Map<string, ExploreKind[]> = initKindsFromStorage()
let globalExpandedUrl: string | null = sessionStorage.getItem(EXPANDED_URL_KEY) || null
let globalScrollTop = 0

const panelRef = ref<HTMLElement>()
const searchKey = ref('')

// 状态：当前展开的书源 URL，优先从模块或会话缓存中恢复
const expandedUrl = ref<string | null>(globalExpandedUrl)
const expandedLoading = ref<Set<string>>(new Set())
// 分类 Map：使用全局缓存初始数据，已经加载过的书源直接呈现分类按钮，绝不转菊花
const expandedKinds = ref<Map<string, ExploreKind[]>>(new Map(globalKindsCache))

const sources = ref<BookSourcePart[]>(globalSourcesCache)

const { showLoading, closeLoading, loadingWrapper, isLoading } = useLoading(
  panelRef,
  '正在获取发现书源',
)

// 过滤语义 (对照 Compose BookSourceDao.flowExplore):
// 仅展示启用了发现 (enabledExplore !== false) 且有发现规则 (hasExploreUrl) 的书源
const filteredSources = computed(() => {
  const key = searchKey.value.trim().toLowerCase()
  if (!key) return sources.value
  return sources.value.filter(
    s =>
      s.bookSourceName.toLowerCase().includes(key) ||
      (s.bookSourceGroup ?? '').toLowerCase().includes(key),
  )
})

/**
 * 切换展开状态 (对照 Compose ExploreScreenModel.toggleExpand)
 * 点击当前已展开的项则收起；点击其他项则单选展开，并优先从缓存读取分类
 */
const toggleExpand = (item: BookSourcePart) => {
  const url = item.bookSourceUrl
  if (expandedUrl.value === url) {
    expandedUrl.value = null
    globalExpandedUrl = null
    sessionStorage.removeItem(EXPANDED_URL_KEY)
  } else {
    expandedUrl.value = url
    globalExpandedUrl = url
    sessionStorage.setItem(EXPANDED_URL_KEY, url)
    loadKinds(item)
  }
}

/**
 * 异步加载书源分类 (对照 Compose ExploreScreenModel.loadKinds + exploreKinds())
 * 优先读取内存及 sessionStorage 缓存，无缓存时才向后端 /getExploreKinds 请求
 */
const loadKinds = async (item: BookSourcePart) => {
  const url = item.bookSourceUrl

  // 1. 本地当前响应式 Map 已有非空分类，直接使用
  if (expandedKinds.value.has(url) && (expandedKinds.value.get(url)?.length ?? 0) > 0) {
    return
  }

  // 2. 全局模块缓存或 sessionStorage 中已有，瞬间还原，杜绝菊花
  if (globalKindsCache.has(url) && (globalKindsCache.get(url)?.length ?? 0) > 0) {
    expandedKinds.value.set(url, globalKindsCache.get(url)!)
    return
  }

  // 3. 无缓存时发起网络请求
  expandedLoading.value.add(url)
  try {
    const resp = await API.getExploreKinds(url)
    if (resp.data.isSuccess && Array.isArray(resp.data.data)) {
      expandedKinds.value.set(url, resp.data.data)
      globalKindsCache.set(url, resp.data.data)
      saveKindsCacheToStorage(globalKindsCache)
    } else {
      expandedKinds.value.set(url, [])
      globalKindsCache.set(url, [])
      saveKindsCacheToStorage(globalKindsCache)
    }
  } catch (e) {
    console.error('[Explore] 获取书源分类失败:', e)
    expandedKinds.value.set(url, [])
  } finally {
    expandedLoading.value.delete(url)
  }
}

/**
 * 依据 FlexChildStyle 计算列跨度 class (对照 Compose GridPackLayout / toGridPackSpec)
 */
const getColClass = (kind: ExploreKind) => {
  const cols = kind.style?.cols
  if (cols === 1) return 'col-1'
  if (cols === 2) return 'col-2'
  if (cols === 4) return 'col-4'
  return 'col-3'
}

/**
 * 点击发现分类 (对照 Compose actions.onOpenExplore):
 * 只要有 URL，无论是普通分类还是 TITLE 分类均可点击跳转至发现详情 ExploreShow
 */
const onOpenExplore = (item: BookSourcePart, kind: ExploreKind) => {
  if (!kind.url) return
  router.push({
    path: '/explore-show',
    query: {
      sourceUrl: item.bookSourceUrl,
      sourceName: item.bookSourceName,
      exploreUrl: kind.url,
      title: kind.title,
    },
  })
}

/**
 * 加载发现书源列表 (优先使用 /getBookSourcesPart, 对照 Compose flowExplore)
 * @param silent 是否静默刷新（已有缓存时不闪现全屏 loading 遮罩）
 */
const loadSources = async (silent = false) => {
  if (!silent) {
    showLoading()
  }
  try {
    // 优先调用轻量化的 /getBookSourcesPart
    let partList: BookSourcePart[] = []
    try {
      const partResp = await API.getBookSourcesPart()
      if (partResp.data.isSuccess && Array.isArray(partResp.data.data)) {
        partList = partResp.data.data
          .filter(s => s.enabledExplore && s.hasExploreUrl)
          .map(s => ({
            bookSourceUrl: s.bookSourceUrl,
            bookSourceName: s.bookSourceName || s.bookSourceUrl,
            bookSourceGroup: s.bookSourceGroup,
            customOrder: s.customOrder ?? 0,
            enabled: s.enabled,
            enabledExplore: s.enabledExplore,
            hasExploreUrl: s.hasExploreUrl,
            hasLoginUrl: s.hasLoginUrl ?? false,
            lastUpdateTime: s.lastUpdateTime ?? 0,
            respondTime: s.respondTime ?? 0,
            weight: s.weight ?? 0,
          }))
      }
    } catch {
      // 降级兜底
    }

    if (partList.length === 0) {
      const { data } = await API.getSources()
      if (data.isSuccess && Array.isArray(data.data)) {
        const all = data.data as BookSource[]
        partList = all
          .filter(s => Boolean((s.exploreUrl ?? '').trim()) && s.enabledExplore !== false)
          .sort((a, b) => (a.customOrder ?? 0) - (b.customOrder ?? 0))
          .map(s => ({
            bookSourceUrl: s.bookSourceUrl,
            bookSourceName: s.bookSourceName || s.bookSourceUrl,
            bookSourceGroup: s.bookSourceGroup,
            customOrder: s.customOrder ?? 0,
            enabled: s.enabled !== false,
            enabledExplore: s.enabledExplore !== false,
            hasExploreUrl: true,
            rawExploreUrl: s.exploreUrl,
            hasLoginUrl: Boolean(s.loginUrl),
            lastUpdateTime: s.lastUpdateTime ?? 0,
            respondTime: s.respondTime ?? 0,
            weight: s.weight ?? 0,
          }))
      }
    }

    sources.value = partList
    globalSourcesCache = partList
    sessionStorage.setItem(SOURCES_STORAGE_KEY, JSON.stringify(partList))

    // 自动装载已展开书源的分类（如果尚未加载）
    if (expandedUrl.value) {
      const activeItem = partList.find(s => s.bookSourceUrl === expandedUrl.value)
      if (activeItem && !expandedKinds.value.has(expandedUrl.value)) {
        loadKinds(activeItem)
      }
    }
  } catch (e) {
    if (!silent) {
      toast.error((e as Error)?.message || '加载书源失败')
    }
  } finally {
    if (!silent) {
      closeLoading()
    }
  }
}

/**
 * 记录滚动位置
 */
const onScroll = () => {
  if (panelRef.value) {
    globalScrollTop = panelRef.value.scrollTop
  }
}

/**
 * 恢复滚动位置
 */
const restoreScroll = () => {
  nextTick(() => {
    if (panelRef.value && globalScrollTop > 0) {
      panelRef.value.scrollTop = globalScrollTop
    }
  })
}

onMounted(() => {
  if (sources.value.length > 0) {
    // 存在缓存：直接恢复界面，静默在后台同步最新书源，绝不闪屏
    restoreScroll()
    loadSources(true)
  } else {
    loadingWrapper(loadSources(false))
  }
})

onActivated(() => {
  // 从其他页面（如 ExploreShow 或 Tab）切回时恢复滚动位置
  restoreScroll()
})
</script>

<style lang="scss" scoped>
.explore-wrapper {
  height: 100%;
  width: 100%;
  display: flex;
  flex-direction: column;
  background-color: #f7f7f7;

  // 顶栏: 对照 Compose ExploreTitleBar (高 56dp, padding 0 12dp)
  .page-topbar {
    flex: none;
    height: 52px;
    display: flex;
    align-items: center;
    padding: 0 12px;
    background: #fff;
    border-bottom: 1px solid rgba(128, 128, 128, 0.15);

    .search-field {
      flex: 1;
      height: 34px;
      display: flex;
      align-items: center;
      padding: 0 10px;
      border-radius: 8px;
      background: #f0f2f5;

      .search-icon {
        width: 16px;
        height: 16px;
        color: #999;
        flex-shrink: 0;
      }

      .search-input {
        flex: 1;
        min-width: 0;
        margin-left: 8px;
        border: none;
        outline: none;
        background: transparent;
        font-size: 14px;
        color: var(--web-text, #333);

        &::placeholder {
          color: #aaa;
        }
      }

      .clear-btn {
        width: 20px;
        height: 20px;
        border: none;
        background: transparent;
        cursor: pointer;
        color: #999;
        display: flex;
        align-items: center;
        justify-content: center;
        padding: 0;

        svg {
          width: 14px;
          height: 14px;
        }
      }
    }
  }

  // 列表滚动区 (对照 Compose LazyColumn)
  .explore-scroll {
    flex: 1;
    overflow-y: auto;
    padding: 8px 12px calc(72px + env(safe-area-inset-bottom));

    .source-list {
      max-width: 900px;
      margin: 0 auto;
      display: flex;
      flex-direction: column;
      gap: 6px;

      .source-item {
        background: #fff;
        border-radius: 6px;
        box-shadow: 0 1px 2px rgba(0, 0, 0, 0.03);
        overflow: hidden;

        // 书源标题行 (对照 Compose ExploreSourceItem 中的 Row)
        .item-header {
          display: flex;
          align-items: center;
          justify-content: space-between;
          padding: 12px 14px;
          cursor: pointer;
          user-select: none;

          &:hover {
            background: rgba(0, 0, 0, 0.02);
          }

          .source-name {
            font-size: 14px;
            color: var(--web-text, #333);
            white-space: nowrap;
            overflow: hidden;
            text-overflow: ellipsis;
            flex: 1;
            margin-right: 8px;
          }

          .header-status {
            display: flex;
            align-items: center;
            gap: 6px;

            .spinner {
              width: 14px;
              height: 14px;
              border: 1.5px solid var(--web-primary, #1e80ff);
              border-top-color: transparent;
              border-radius: 50%;
              animation: spin 0.8s linear infinite;
            }

            .arrow-icon {
              width: 18px;
              height: 18px;
              color: #999;
              transition: transform 0.2s ease;

              &.rotated {
                transform: rotate(90deg);
              }
            }
          }
        }

        // 分类网格区 (对照 Compose KindFlow)
        .item-body {
          padding: 8px 14px 12px;
          border-top: 1px dashed rgba(0, 0, 0, 0.06);
          background: #fafbfc;

          .kinds-layout {
            display: flex;
            flex-wrap: wrap;
            gap: 8px;

            .kind-btn {
              padding: 6px 10px;
              border-radius: 6px;
              border: 1px solid #e2e8f0;
              background: #fff;
              font-size: 13px;
              color: #475569;
              cursor: pointer;
              transition: all 0.15s;
              text-align: center;
              overflow: hidden;
              text-overflow: ellipsis;
              white-space: nowrap;
              box-sizing: border-box;

              &.col-1 { width: 100%; }
              &.col-2 { width: calc((100% - 8px) / 2); }
              &.col-3 { width: calc((100% - 16px) / 3); }
              &.col-4 { width: calc((100% - 24px) / 4); }

              &.is-title {
                font-weight: 600;
              }

              &.no-url {
                cursor: default;
                opacity: 0.85;
              }

              &:not(.no-url):hover {
                border-color: var(--web-primary, #1e80ff);
                color: var(--web-primary, #1e80ff);
                background: #f0f7ff;
              }
            }
          }

          .empty-kinds {
            font-size: 12px;
            color: #999;
            padding: 4px 0;
          }
        }
      }
    }

    .empty {
      margin-top: 60px;
      text-align: center;
      font-size: 14px;
      color: #999;
    }
  }
}

.night {
  background-color: #161819;

  .page-topbar {
    background: #242526;
    border-bottom-color: rgba(255, 255, 255, 0.08);

    .search-field {
      background: #333;

      .search-input {
        color: #aeaeae;

        &::placeholder {
          color: #777;
        }
      }
    }
  }

  .explore-scroll .source-list .source-item {
    background: #242526;
    box-shadow: none;

    .item-header {
      .source-name {
        color: #ddd;
      }

      .header-status .arrow-icon {
        color: #777;
      }
    }

    .item-body {
      background: #1c1d1e;
      border-top-color: #333;

      .kinds-layout {
        .kind-btn {
          background: #2b2c2e;
          border-color: #3a3b3d;
          color: #bbb;

          &:not(.no-url):hover {
            border-color: #60a5fa;
            color: #60a5fa;
            background: #1e293b;
          }
        }
      }

      .empty-kinds {
        color: #777;
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
