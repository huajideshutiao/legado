<template>
  <div
    v-if="visible"
    class="catalog-dialog-mask"
    :class="{ night: isNight }"
    @click.self="handleClose"
  >
    <div class="catalog-dialog">
      <!-- 头部：标题与工具 -->
      <div class="dialog-header">
        <div class="dialog-title-wrap">
          <div class="dialog-title">书籍目录</div>
          <div class="dialog-count">共 {{ catalogList.length }} 章</div>
        </div>
        <div class="dialog-tools">
          <button
            class="tool-mini-btn"
            type="button"
            @click="catalogReverse = !catalogReverse"
          >
            {{ catalogReverse ? '正序' : '倒序' }}
          </button>
          <button class="dialog-close-btn" type="button" aria-label="关闭" @click="handleClose">
            ✕
          </button>
        </div>
      </div>

      <!-- 搜索过滤栏 -->
      <div class="dialog-search">
        <input
          ref="searchInputRef"
          class="catalog-search-input"
          v-model="catalogSearch"
          placeholder="搜索章节名..."
        />
        <button
          v-if="catalogSearch"
          class="clear-search-btn"
          type="button"
          @click="catalogSearch = ''"
        >
          ✕
        </button>
      </div>

      <!-- 章节列表 -->
      <div class="dialog-list" ref="listRef">
        <div
          v-for="chap in displayCatalog"
          :key="chap.displayIndex"
          class="dialog-chapter-item"
          :class="{ active: chap.displayIndex === currentChapterIndex }"
          @click="handleSelect(chap.displayIndex)"
        >
          <span class="chap-idx">{{ chap.displayIndex + 1 }}.</span>
          <span class="chap-title">{{ chap.title }}</span>
          <span class="chap-badge" v-if="chap.displayIndex === currentChapterIndex">当前阅读</span>
        </div>
        <div class="dialog-empty" v-if="displayCatalog.length === 0">
          无匹配章节
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, nextTick } from 'vue'
import type { BookChapter } from '@/book'

const props = withDefaults(
  defineProps<{
    visible: boolean
    catalog?: BookChapter[]
    currentChapterIndex?: number
    isNight?: boolean
  }>(),
  {
    visible: false,
    catalog: () => [],
    currentChapterIndex: 0,
    isNight: false,
  },
)

const emit = defineEmits<{
  (e: 'update:visible', val: boolean): void
  (e: 'close'): void
  (e: 'select', chapterIndex: number): void
}>()

const catalogSearch = ref('')
const catalogReverse = ref(false)
const listRef = ref<HTMLElement>()
const searchInputRef = ref<HTMLInputElement>()

interface NormalizedChapter extends BookChapter {
  displayIndex: number
}

const catalogList = computed<NormalizedChapter[]>(() => {
  const raw = props.catalog || []
  return raw.map((chap, i) => ({
    ...chap,
    displayIndex: typeof chap.index === 'number' ? chap.index : i,
  }))
})

const displayCatalog = computed(() => {
  let list = catalogList.value
  const q = catalogSearch.value.trim().toLowerCase()
  if (q) {
    list = list.filter(c => c.title.toLowerCase().includes(q))
  }
  if (catalogReverse.value) {
    return [...list].reverse()
  }
  return list
})

const handleClose = () => {
  emit('update:visible', false)
  emit('close')
}

const handleSelect = (index: number) => {
  emit('select', index)
  emit('update:visible', false)
  emit('close')
}

// 弹窗打开时重置搜索并自动定位到当前章节
watch(
  () => props.visible,
  val => {
    if (val) {
      catalogSearch.value = ''
      nextTick(() => {
        if (!listRef.value) return
        const activeItem = listRef.value.querySelector('.dialog-chapter-item.active')
        if (activeItem) {
          activeItem.scrollIntoView({ block: 'center' })
        }
      })
    }
  },
)
</script>

<style lang="scss" scoped>
.catalog-dialog-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.5);
  z-index: 1000;
  display: flex;
  justify-content: center;
  align-items: flex-end;

  @media (min-width: 640px) {
    align-items: center;
  }
}

.catalog-dialog {
  width: 100%;
  max-width: 560px;
  height: 82vh;
  max-height: 82vh;
  background: #ffffff;
  color: #1f2937;
  border-radius: 16px 16px 0 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  box-shadow: 0 -4px 24px rgba(0, 0, 0, 0.18);

  @media (min-width: 640px) {
    border-radius: 16px;
    height: 75vh;
    max-height: 75vh;
  }

  .dialog-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 14px 16px;
    border-bottom: 1px solid #eef0f3;
    flex-shrink: 0;

    .dialog-title-wrap {
      display: flex;
      align-items: baseline;
      gap: 8px;

      .dialog-title {
        font-size: 16px;
        font-weight: 700;
      }
      .dialog-count {
        font-size: 12px;
        color: #9ca3af;
      }
    }

    .dialog-tools {
      display: flex;
      align-items: center;
      gap: 8px;

      .tool-mini-btn {
        padding: 4px 10px;
        border-radius: 14px;
        border: 1px solid #d1d5db;
        background: transparent;
        color: inherit;
        font-size: 12px;
        cursor: pointer;
        transition: all 0.2s;

        &:hover {
          border-color: #3b82f6;
          color: #3b82f6;
        }
      }

      .dialog-close-btn {
        border: none;
        background: transparent;
        color: #9ca3af;
        font-size: 16px;
        cursor: pointer;
        padding: 4px 8px;
        line-height: 1;

        &:hover {
          color: #374151;
        }
      }
    }
  }

  .dialog-search {
    position: relative;
    padding: 8px 16px;
    border-bottom: 1px solid #eef0f3;
    flex-shrink: 0;

    .catalog-search-input {
      width: 100%;
      box-sizing: border-box;
      height: 36px;
      border-radius: 8px;
      border: 1px solid #d1d5db;
      padding: 0 32px 0 12px;
      background: #f9fafb;
      color: inherit;
      font-size: 13px;
      outline: none;
      transition: border-color 0.2s;

      &:focus {
        border-color: #3b82f6;
        background: #fff;
      }
    }

    .clear-search-btn {
      position: absolute;
      right: 24px;
      top: 50%;
      transform: translateY(-50%);
      border: none;
      background: transparent;
      color: #9ca3af;
      font-size: 12px;
      cursor: pointer;
      padding: 4px;

      &:hover {
        color: #4b5563;
      }
    }
  }

  .dialog-list {
    flex: 1;
    overflow-y: auto;
    padding: 8px;

    .dialog-chapter-item {
      display: flex;
      align-items: center;
      padding: 11px 12px;
      border-radius: 8px;
      font-size: 14px;
      cursor: pointer;
      transition: background 0.15s;
      content-visibility: auto;
      contain-intrinsic-size: 44px;

      &:hover {
        background: #f3f4f6;
      }

      &.active {
        background: #ecf5ff;
        color: #3b82f6;
        font-weight: 600;
      }

      .chap-idx {
        width: 44px;
        color: #9ca3af;
        font-size: 12px;
        flex-shrink: 0;
      }

      .chap-title {
        flex: 1;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }

      .chap-badge {
        padding: 1px 6px;
        background: #3b82f6;
        color: #fff;
        font-size: 11px;
        border-radius: 4px;
        flex-shrink: 0;
        margin-left: 8px;
      }
    }

    .dialog-empty {
      padding: 32px;
      text-align: center;
      color: #9ca3af;
      font-size: 13px;
    }
  }
}

// 暗色主题样式
.catalog-dialog-mask.night {
  .catalog-dialog {
    background: #1e1e1e;
    color: #e0e0e0;
    box-shadow: 0 -4px 24px rgba(0, 0, 0, 0.5);

    .dialog-header {
      border-bottom-color: #2c2c2c;

      .dialog-title-wrap .dialog-count {
        color: #888888;
      }

      .dialog-tools {
        .tool-mini-btn {
          border-color: #444444;
          color: #cccccc;
          &:hover {
            border-color: #60a5fa;
            color: #60a5fa;
          }
        }
        .dialog-close-btn {
          color: #888888;
          &:hover {
            color: #ffffff;
          }
        }
      }
    }

    .dialog-search {
      border-bottom-color: #2c2c2c;

      .catalog-search-input {
        background: #282828;
        border-color: #3a3a3a;
        color: #e0e0e0;

        &:focus {
          border-color: #60a5fa;
          background: #303030;
        }
      }

      .clear-search-btn {
        color: #777777;
        &:hover {
          color: #bbbbbb;
        }
      }
    }

    .dialog-list {
      .dialog-chapter-item {
        &:hover {
          background: rgba(255, 255, 255, 0.06);
        }

        &.active {
          background: rgba(59, 130, 246, 0.18);
          color: #60a5fa;
        }

        .chap-idx {
          color: #777777;
        }

        .chap-badge {
          background: #2563eb;
        }
      }

      .dialog-empty {
        color: #777777;
      }
    }
  }
}
</style>
