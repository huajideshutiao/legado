<template>
  <div :class="{ 'explore-show-page': true, night: isNight, day: !isNight }">
    <!-- 顶栏：返回 + 分类标题与书源名 (右上角已移除刷新按钮) -->
    <header class="page-topbar">
      <button class="topbar-btn back-btn" type="button" aria-label="返回" @click="goBack">
        <svg viewBox="0 0 24 24" fill="currentColor">
          <path d="M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z" />
        </svg>
      </button>

      <div class="topbar-title-wrap">
        <h1 class="topbar-title">{{ categoryTitle || '发现详情' }}</h1>
        <span v-if="sourceName" class="topbar-subtitle">{{ sourceName }}</span>
      </div>
    </header>

    <!-- 列表滚动容器 (对齐书架 shelf-scroll 结构与样式) -->
    <div class="shelf-scroll" ref="scrollRef" @scroll="onScroll">
      <!-- 正在初次加载 -->
      <div v-if="isLoading && books.length === 0" class="center-state">
        <div class="spinner"></div>
        <span>正在加载书籍...</span>
      </div>

      <!-- 加载失败且无数据 -->
      <div v-else-if="errorMsg && books.length === 0" class="center-state error">
        <p>{{ errorMsg }}</p>
        <button class="retry-btn" type="button" @click="refresh">点击重试</button>
      </div>

      <!-- 书籍列表：直接复用书架 book-items 组件，样式与书架完全一致 -->
      <div v-else-if="books.length > 0" class="explore-books-wrap">
        <book-items
          :books="books"
          :isSearch="true"
          @bookClick="handleBookClick"
        />

        <!-- 底部加载状态 -->
        <div class="list-footer">
          <div v-if="isLoading" class="footer-loading">
            <div class="spinner small"></div>
            <span>正在加载下一页...</span>
          </div>
          <div v-else-if="!hasMore" class="footer-end">
            已经到底了
          </div>
          <button v-else class="footer-load-more" type="button" @click="loadNextPage">
            点击加载更多
          </button>
        </div>
      </div>

      <!-- 空状态 -->
      <div v-else class="center-state">
        <p>暂无相关书籍</p>
        <button class="retry-btn" type="button" @click="refresh">重新加载</button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
defineOptions({ name: 'ExploreShow' })

import { ref, computed, onMounted, onActivated, onDeactivated, nextTick, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import BookItems from '@/components/BookItems.vue'
import { useBookStore } from '@/store'
import API from '@api'
import type { SeachBook, Book } from '@/book'

const route = useRoute()
const router = useRouter()
const store = useBookStore()
const isNight = computed(() => store.isNight)

const sourceUrl = computed(() => String(route.query.sourceUrl || ''))
const sourceName = computed(() => String(route.query.sourceName || ''))
const exploreUrl = computed(() => String(route.query.exploreUrl || ''))
const categoryTitle = computed(() => String(route.query.title || ''))

const books = ref<SeachBook[]>([])
const page = ref(1)
const isLoading = ref(false)
const hasMore = ref(true)
const errorMsg = ref('')

const scrollRef = ref<HTMLElement>()
const savedScrollTop = ref(0)
const lastLoadedKey = ref('')

const currentQueryKey = computed(() => `${sourceUrl.value}:::${exploreUrl.value}`)

const goBack = () => {
  if (window.history.length > 1) {
    router.back()
  } else {
    router.push('/explore')
  }
}

/**
 * 书籍点击逻辑（与书架 handleBookClick 对齐）：进入书籍详情页
 */
const handleBookClick = (book: SeachBook | Book) => {
  store.setDetailBook(book)
  router.push({
    path: '/book-info',
    query: { bookUrl: book.bookUrl },
  })
}

const loadBooks = async (reset = false) => {
  if (isLoading.value) return
  if (!sourceUrl.value || !exploreUrl.value) {
    errorMsg.value = '缺少书源或分类参数'
    return
  }

  if (reset) {
    books.value = []
    page.value = 1
    hasMore.value = true
    errorMsg.value = ''
    lastLoadedKey.value = currentQueryKey.value
  }

  isLoading.value = true
  try {
    const curPage = page.value
    const resp = await API.getExploreBooks(sourceUrl.value, exploreUrl.value, curPage)
    if (resp.data.isSuccess && Array.isArray(resp.data.data)) {
      const list = resp.data.data as SeachBook[]
      if (reset) {
        books.value = list
      } else {
        const existing = new Set(books.value.map(b => b.bookUrl))
        const newItems = list.filter(b => !existing.has(b.bookUrl))
        books.value.push(...newItems)
      }

      if (list.length === 0) {
        hasMore.value = false
      } else {
        page.value = curPage + 1
      }
    } else {
      if (reset) {
        books.value = []
        errorMsg.value = resp.data.errorMsg || '加载分类书籍失败'
      }
      hasMore.value = false
    }
  } catch (e: any) {
    console.error('[ExploreShow] 加载失败:', e)
    if (reset) {
      errorMsg.value = e?.message || '网络请求失败，请稍后重试'
    }
  } finally {
    isLoading.value = false
  }
}

const refresh = () => {
  loadBooks(true)
}

const loadNextPage = () => {
  if (hasMore.value && !isLoading.value) {
    loadBooks(false)
  }
}

const onScroll = () => {
  const el = scrollRef.value
  if (!el) return
  savedScrollTop.value = el.scrollTop
  if (isLoading.value || !hasMore.value) return
  if (el.scrollHeight - el.scrollTop - el.clientHeight < 120) {
    loadNextPage()
  }
}

const checkAndLoad = () => {
  if (!sourceUrl.value || !exploreUrl.value) return
  if (currentQueryKey.value !== lastLoadedKey.value) {
    books.value = []
    savedScrollTop.value = 0
    if (scrollRef.value) scrollRef.value.scrollTop = 0
    refresh()
  } else if (books.value.length === 0 && !isLoading.value && !errorMsg.value) {
    refresh()
  }
}

watch(currentQueryKey, (newVal, oldVal) => {
  if (newVal && newVal !== oldVal && route.path === '/explore-show') {
    books.value = []
    savedScrollTop.value = 0
    if (scrollRef.value) scrollRef.value.scrollTop = 0
    checkAndLoad()
  }
})

onDeactivated(() => {
  if (scrollRef.value) {
    savedScrollTop.value = scrollRef.value.scrollTop
  }
})

onActivated(() => {
  if (currentQueryKey.value !== lastLoadedKey.value) {
    books.value = []
    savedScrollTop.value = 0
    if (scrollRef.value) scrollRef.value.scrollTop = 0
  }
  checkAndLoad()
  nextTick(() => {
    if (scrollRef.value && savedScrollTop.value > 0) {
      scrollRef.value.scrollTop = savedScrollTop.value
    }
  })
})

onMounted(() => {
  checkAndLoad()
})
</script>

<style scoped lang="scss">
.explore-show-page {
  display: flex;
  flex-direction: column;
  height: 100vh;
  width: 100%;
  background-color: #f7f7f7;
  color: #333;
  overflow: hidden;

  &.night {
    background-color: #161819;
    color: #e4e6eb;

    .page-topbar {
      background: #242526;
      border-bottom-color: rgba(255, 255, 255, 0.08);

      .topbar-btn {
        color: #e4e6eb;
        &:hover {
          background: rgba(255, 255, 255, 0.08);
        }
      }

      .topbar-title {
        color: #e4e6eb;
      }
      .topbar-subtitle {
        color: #888;
      }
    }

    :deep(.books-wrapper) {
      .wrapper .book {
        &:hover {
          background: rgba(255, 255, 255, 0.06);
        }
        .info {
          .name {
            color: #e4e6eb;
          }
          .sub {
            color: #8a8a8a;
            .tags .web-tag {
              background: #2b2c2e;
              color: #aaa;
              border-color: #3a3b3d;
            }
          }
          .intro,
          .dur-chapter,
          .last-chapter {
            color: #8a8a8a;
          }
        }
      }
    }

    .list-footer {
      color: #777;
      .footer-load-more {
        background: #242526;
        border-color: #3a3b3d;
        color: #aaa;
        &:hover {
          color: #60a5fa;
          border-color: #60a5fa;
        }
      }
    }
  }
}

.page-topbar {
  display: flex;
  align-items: center;
  height: 48px;
  padding: 0 12px;
  background: #fff;
  border-bottom: 1px solid rgba(128, 128, 128, 0.15);
  z-index: 10;
  flex-shrink: 0;

  .topbar-btn {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 36px;
    height: 36px;
    border-radius: 50%;
    border: none;
    background: transparent;
    color: #4b5563;
    cursor: pointer;
    transition: background 0.15s;

    &:hover {
      background: rgba(0, 0, 0, 0.05);
    }

    svg {
      width: 20px;
      height: 20px;
    }
  }

  .topbar-title-wrap {
    flex: 1;
    margin-left: 8px;
    display: flex;
    flex-direction: column;
    justify-content: center;
    overflow: hidden;

    .topbar-title {
      font-size: 16px;
      font-weight: 600;
      line-height: 1.2;
      margin: 0;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }

    .topbar-subtitle {
      font-size: 11px;
      color: #6b7280;
      margin-top: 2px;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }
  }
}

/* 列表滚动容器：去掉顶部底部边距，对齐书架 */
.shelf-scroll {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  padding: 0;
  box-sizing: border-box;

  .explore-books-wrap {
    max-width: 900px;
    margin: 0 auto;
    width: 100%;

    :deep(.books-wrapper) {
      overflow: visible;
    }
  }
}

.center-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 260px;
  color: #6b7280;
  gap: 12px;

  &.error {
    color: #ef4444;
  }

  .retry-btn {
    padding: 6px 16px;
    border-radius: 6px;
    border: 1px solid #d1d5db;
    background: transparent;
    color: inherit;
    cursor: pointer;
    font-size: 13px;
  }
}

.list-footer {
  display: flex;
  justify-content: center;
  align-items: center;
  padding: 12px 0;
  font-size: 13px;
  color: #9ca3af;

  .footer-loading {
    display: flex;
    align-items: center;
    gap: 8px;
  }

  .footer-load-more {
    padding: 6px 16px;
    border-radius: 6px;
    border: 1px solid #d1d5db;
    background: #fff;
    color: #4b5563;
    font-size: 13px;
    cursor: pointer;
    transition: all 0.15s;

    &:hover {
      border-color: #3b82f6;
      color: #3b82f6;
    }
  }
}

.spinner {
  width: 24px;
  height: 24px;
  border: 2px solid #3b82f6;
  border-top-color: transparent;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;

  &.small {
    width: 16px;
    height: 16px;
    border-width: 2px;
  }
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
