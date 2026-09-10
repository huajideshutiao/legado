<template>
  <div :class="{ 'shelf-page': true, night: isNight, day: !isNight }">
    <!-- 顶栏 = 分组 tab 行 (AppScrollTabRow) + 右侧搜索图标, 对照 Compose 书架样式1 -->
    <header class="page-topbar">
      <div
        class="group-tabs"
        role="tablist"
        ref="tabsRef"
        @wheel.passive="handleTabsWheel"
        @mousedown="onTabsMouseDown"
        @mousemove="onTabsMouseMove"
        @mouseup="onTabsMouseUp"
        @mouseleave="onTabsMouseLeave"
      >
        <button
          v-for="group in groups"
          :key="group.groupId"
          type="button"
          class="group-tab"
          :class="{ active: currentGroupId === group.groupId }"
          @click="handleGroupTabClick(group.groupId)"
        >
          {{ group.groupName }}
        </button>
      </div>
      <button
        class="topbar-icon"
        type="button"
        aria-label="搜索"
        @click="goToSearch"
      >
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path
            d="M15.5 14h-.79l-.28-.27a6.5 6.5 0 1 0-.7.7l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0A4.5 4.5 0 1 1 14 9.5 4.5 4.5 0 0 1 9.5 14z"
          />
        </svg>
      </button>
    </header>

    <div class="shelf-scroll" ref="shelfWrapper">
      <book-items
        :books="shelf"
        :isSearch="false"
        @bookClick="handleBookClick"
      ></book-items>
    </div>
  </div>
</template>

<script setup lang="ts">
defineOptions({ name: 'BookShelf' })

import '@/assets/bookshelf.css'
import { useBookStore } from '@/store'
import { useLoading } from '@/hooks/loading'
import type { Book } from '@/book'

const store = useBookStore()
const isNight = computed(() => store.isNight)

const shelfWrapper = ref<HTMLElement>()
const { showLoading, closeLoading, loadingWrapper } = useLoading(
  shelfWrapper,
  '正在获取书籍信息',
)

const shelf = computed(() => store.shelf)
const groups = computed(() => store.groups)
const currentGroupId = ref<number | string | undefined>(undefined)

const router = useRouter()
const goToSearch = () => {
  router.push({
    path: '/search',
    query: currentGroupId.value !== undefined ? { shelfGroupId: String(currentGroupId.value) } : {},
  })
}

const handleBookClick = (book: Book) => {
  store.setDetailBook(book)
  router.push({
    path: '/book-info',
    query: { bookUrl: book.bookUrl },
  })
}

const loadShelf = async () => {
  await store.loadWebConfig()
  await store.saveBookProgress()
  await store.loadGroups()
  if (groups.value.length > 0) {
    currentGroupId.value = store.currentGroupId ?? groups.value[0].groupId
    await store.loadBookShelf(currentGroupId.value)
  } else {
    await store.loadBookShelf()
  }
}

const tabsRef = ref<HTMLElement>()

// 滚轮横向滚动支持 (上下滚动自动转横向滑动)
const handleTabsWheel = (e: WheelEvent) => {
  if (!tabsRef.value) return
  if (Math.abs(e.deltaY) > Math.abs(e.deltaX)) {
    tabsRef.value.scrollLeft += e.deltaY
  }
}

// 鼠标按住拖拽左右滑动 (Drag to scroll)
let isTabsDragging = false
let tabsStartX = 0
let tabsStartScrollLeft = 0
let tabsHasDragged = false

const onTabsMouseDown = (e: MouseEvent) => {
  if (!tabsRef.value) return
  isTabsDragging = true
  tabsHasDragged = false
  tabsStartX = e.pageX - tabsRef.value.offsetLeft
  tabsStartScrollLeft = tabsRef.value.scrollLeft
}

const onTabsMouseMove = (e: MouseEvent) => {
  if (!isTabsDragging || !tabsRef.value) return
  const x = e.pageX - tabsRef.value.offsetLeft
  const walk = x - tabsStartX
  if (Math.abs(walk) > 4) {
    tabsHasDragged = true
    e.preventDefault()
  }
  tabsRef.value.scrollLeft = tabsStartScrollLeft - walk
}

const onTabsMouseUp = () => {
  isTabsDragging = false
}

const onTabsMouseLeave = () => {
  isTabsDragging = false
}

const handleGroupTabClick = (groupId: number | string) => {
  if (tabsHasDragged) return
  switchGroup(groupId)
}

const switchGroup = async (groupId: number | string) => {
  if (currentGroupId.value === groupId) return
  currentGroupId.value = groupId
  const hasCache = store.hasGroupCache(groupId)
  if (!hasCache) {
    showLoading()
  }
  try {
    await store.loadBookShelf(groupId)
  } finally {
    if (!hasCache) {
      closeLoading()
    }
  }
}

onMounted(() => {
  loadingWrapper(loadShelf())
})
</script>

<style lang="scss" scoped>
.shelf-page {
  height: 100%;
  width: 100%;
  display: flex;
  flex-direction: column;
  background-color: #f7f7f7;
  padding-bottom: calc(56px + env(safe-area-inset-bottom));
  box-sizing: border-box;

  .page-topbar {
    flex: none;
    height: 48px;
    display: flex;
    align-items: center;
    padding-left: 4px;
    padding-right: 8px;
    border-bottom: 1px solid rgba(128, 128, 128, 0.15);

    .group-tabs {
      flex: 1;
      min-width: 0;
      display: flex;
      gap: 4px;
      overflow-x: auto;
      overflow-y: hidden;
      white-space: nowrap;
      scrollbar-width: none;
      -webkit-overflow-scrolling: touch;
      touch-action: pan-x;
      cursor: grab;
      user-select: none;

      &:active {
        cursor: grabbing;
      }

      &::-webkit-scrollbar {
        display: none;
      }

      .group-tab {
        flex: none;
        border: 0;
        background: transparent;
        padding: 12px 12px;
        font-size: 14px;
        color: var(--web-text-secondary);
        cursor: pointer;
        border-bottom: 2px solid transparent;
        white-space: nowrap;

        &.active {
          color: var(--web-primary);
          border-bottom-color: var(--web-primary);
          font-weight: 600;
        }
      }
    }

    .topbar-icon {
      flex: none;
      border: 0;
      background: transparent;
      width: 36px;
      height: 36px;
      padding: 0;
      cursor: pointer;
      color: #6b6b6b;

      svg {
        width: 22px;
        height: 22px;
        fill: currentColor;
      }
    }
  }

  .shelf-scroll {
    flex: 1;
    overflow: hidden;
    display: flex;
    flex-direction: column;
    padding: 0;
    box-sizing: border-box;
  }
}

.night {
  background-color: #161819;

  .page-topbar .topbar-icon {
    color: #8a8a8a;
  }

  .group-tabs {
    border-bottom-color: rgba(128, 128, 128, 0.25);

    .group-tab {
      color: #8a8a8a;

      &.active {
        color: var(--web-primary);
      }
    }
  }
}
</style>
