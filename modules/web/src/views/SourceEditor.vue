<template>
  <div :class="{ 'source-editor-page': true, night: isNight, day: !isNight }">
    <!-- 顶栏 -->
    <header class="editor-topbar">
      <div class="topbar-left">
        <button class="topbar-btn back-btn" type="button" aria-label="返回" @click="goBack" title="返回书源管理">
          <svg viewBox="0 0 24 24" fill="currentColor">
            <path d="M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z" />
          </svg>
        </button>
        <h1 class="topbar-title">书源编辑</h1>
      </div>

      <div class="topbar-right">
        <!-- 复制JSON按钮 -->
        <button class="action-btn" type="button" @click="copySourceJson" title="复制书源JSON">
          <svg viewBox="0 0 24 24" fill="currentColor">
            <path d="M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 16H8V7h11v14z" />
          </svg>
          <span>复制JSON</span>
        </button>

        <!-- 调试按钮 -->
        <button
          class="action-btn"
          :class="{ active: activeRightTab === 'editDebug' }"
          type="button"
          @click="toggleDebug"
          title="调试面板 (快捷键: Ctrl+D)"
        >
          <svg viewBox="0 0 24 24" fill="currentColor">
            <path d="M19 12l-7-7v4H5v6h7v4z" />
          </svg>
          <span>调试</span>
        </button>

        <!-- 帮助信息按钮 -->
        <button
          class="action-btn"
          :class="{ active: activeRightTab === 'editHelp' }"
          type="button"
          @click="toggleHelp"
          title="帮助信息"
        >
          <svg viewBox="0 0 24 24" fill="currentColor">
            <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 16h-2v-2h2v2zm1.07-7.75l-.9.92C12.45 11.9 12 12.5 12 14h-2v-.5c0-1.1.45-2.1 1.17-2.83l1.24-1.26c.37-.36.59-.86.59-1.41 0-1.1-.9-2-2-2s-2 .9-2 2H7c0-2.76 2.24-5 5-5s5 2.24 5 5c0 1.04-.42 1.99-1.07 2.75z" />
          </svg>
          <span>帮助</span>
        </button>

        <!-- 保存按钮 -->
        <button class="action-btn primary" type="button" @click="saveSource" title="快捷键: Ctrl+S">
          <svg viewBox="0 0 24 24" fill="currentColor">
            <path d="M17 3H5c-1.11 0-2 .9-2 2v14c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2V7l-4-4zm-5 16c-1.66 0-3-1.34-3-3s1.34-3 3-3 3 1.34 3 3-1.34 3-3 3zm3-10H5V5h10v4z" />
          </svg>
          <span>保存</span>
        </button>

        <!-- 快捷键按钮 -->
        <button class="action-btn secondary" type="button" @click="hotkeysDialogVisible = true" title="快捷键设置">
          <svg viewBox="0 0 24 24" fill="currentColor">
            <path d="M20 5H4c-1.1 0-1.99.9-1.99 2L2 17c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm-9 3h2v2h-2V8zm0 3h2v2h-2v-2zM8 8h2v2H8V8zm0 3h2v2H8v-2zm-1 2H5v-2h2v2zm0-3H5V8h2v2zm9 7H8v-2h8v2zm0-4h-2v-2h2v2zm0-3h-2V8h2v2zm3 3h-2v-2h2v2zm0-3h-2V8h2v2z" />
          </svg>
          <span>快捷键</span>
        </button>
      </div>
    </header>

    <!-- 主体编辑区域 (单栏/双栏布局) -->
    <div class="editor-content">
      <div class="left-panel">
        <source-tab-form :config="config" />
      </div>
      <div v-if="activeRightTab" class="right-panel">
        <source-tab-tools :activeTab="activeRightTab" />
      </div>
    </div>

    <!-- 快捷键设置弹窗 -->
    <div v-if="hotkeysDialogVisible" class="web-dialog-overlay" @click.self="stopRecordKeyDown">
      <div class="web-dialog hotkeys-modal">
        <div class="web-dialog__header">
          <span>
            快捷键设置
            <span v-if="recordKeyDowning" class="web-text web-text--secondary"> / 录入中 </span>
          </span>
          <div class="dialog-header-actions">
            <button class="web-btn web-btn--primary" :disabled="recordKeyDowning" @click="saveHotKeys">保存</button>
            <button class="web-dialog__close" @click="stopRecordKeyDown">&times;</button>
          </div>
        </div>
        <div class="hotkeys-settings">
          <div v-for="(button, buttonIndex) in shortcutActions" :key="button.name" class="hotkeys-item">
            <span class="action-name">{{ button.name }}</span>
            <div class="hotkeys-keys">
              <template v-for="(key, hotKeysIndex) in button.hotKeys" :key="key">
                <kbd>{{ key }}</kbd>
                <span v-if="hotKeysIndex + 1 < button.hotKeys.length" class="plus">+</span>
              </template>
              <span v-if="button.hotKeys.length === 0" class="empty-tip">未设置</span>
            </div>
            <button
              class="web-btn web-btn--text edit-btn"
              :disabled="recordKeyDowning"
              @click="recordKeyDown(buttonIndex)"
            >
              {{ recordKeyDowning && recordKeyDownIndex === buttonIndex ? '录入中...' : '录入' }}
            </button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import bookSourceConfig from '@/config/bookSourceEditConfig'
import '@/assets/sourceeditor.css'
import type { SourceConfig } from '@/config/sourceConfig'
import { useSourceStore } from '@/store/sourceStore'
import { useBookStore } from '@/store'
import API from '@api'
import hotkeys from 'hotkeys-js'
import { getSourceName, isInvaildSource, normalizeSource } from '../utils/souce'
import { toast } from '@/utils/toast'

const router = useRouter()
const store = useSourceStore()
const bookStore = useBookStore()
const isNight = computed(() => bookStore.isNight)

const config: SourceConfig = bookSourceConfig as SourceConfig

const goBack = () => {
  if (window.history.length > 1) {
    router.back()
  } else {
    router.push('/sources')
  }
}

// 复制书源JSON
const copySourceJson = async () => {
  const source = store.currentSource
  if (!source) {
    toast.error('书源数据为空')
    return
  }
  try {
    const clone = JSON.parse(JSON.stringify(source))
    normalizeSource(clone as Record<string, unknown>)
    const jsonStr = JSON.stringify(clone, null, 2)
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(jsonStr)
    } else {
      const textarea = document.createElement('textarea')
      textarea.value = jsonStr
      textarea.style.position = 'fixed'
      textarea.style.opacity = '0'
      document.body.appendChild(textarea)
      textarea.select()
      document.execCommand('copy')
      document.body.removeChild(textarea)
    }
    toast.success('已复制书源JSON到剪贴板')
  } catch (e: any) {
    toast.error('复制失败: ' + (e?.message || e))
  }
}

const activeRightTab = ref<'editDebug' | 'editHelp' | null>(null)

// 调试书源 (Toggle 开关)
const toggleDebug = () => {
  if (activeRightTab.value === 'editDebug') {
    activeRightTab.value = null
  } else {
    activeRightTab.value = 'editDebug'
    store.currentTab = 'editDebug'
    store.startDebug()
    toast.info('已开启调试面板')
  }
}

// 帮助信息 (Toggle 开关)
const toggleHelp = () => {
  if (activeRightTab.value === 'editHelp') {
    activeRightTab.value = null
  } else {
    activeRightTab.value = 'editHelp'
    store.currentTab = 'editHelp'
  }
}

// 快捷键调试
const debug = () => {
  activeRightTab.value = 'editDebug'
  store.currentTab = 'editDebug'
  store.startDebug()
  toast.info('已开启调试模式')
}

// 保存书源
const saveSource = () => {
  const source = store.currentSource
  if (isInvaildSource(source)) {
    normalizeSource(source as Record<string, unknown>)
    API.saveSource(source).then(({ data }) => {
      const sourceName = getSourceName(source)
      if (data.isSuccess) {
        toast.success(`源《${sourceName}》已成功保存到「阅读3.0」`)
        store.saveCurrentSource()
      } else {
        toast.error(`源《${sourceName}》保存失败: ${data.errorMsg}`)
      }
    })
  } else {
    toast.error('请检查书源名称、地址等必填项')
  }
}

// 快捷键定义列表：只保留调试与保存
const shortcutActions = ref<{ name: string; hotKeys: string[]; action: () => void }[]>([
  { name: '保存书源', hotKeys: ['ctrl', 's'], action: saveSource },
  { name: '调试书源', hotKeys: ['ctrl', 'd'], action: debug },
])

const hotkeysDialogVisible = ref(false)
const recordKeyDowning = ref(false)
const recordKeyDownIndex = ref(-1)

const stopRecordKeyDown = () => {
  if (!recordKeyDowning.value) {
    hotkeysDialogVisible.value = false
  }
  recordKeyDowning.value = false
}

const recordKeyDown = (index: number) => {
  recordKeyDowning.value = true
  toast.info('请按下快捷键组合，按 ESC 退出')
  shortcutActions.value[index].hotKeys = []
  recordKeyDownIndex.value = index
}

const saveHotKeys = () => {
  const hotKeysConfig: string[][] = []
  shortcutActions.value.forEach(({ hotKeys }) => {
    hotKeysConfig.push(hotKeys)
  })
  localStorage.setItem('legado_web_editor_hotkeys', JSON.stringify(hotKeysConfig))
  hotkeysDialogVisible.value = false
  bindHotKeys()
  toast.success('快捷键设置已保存')
}

const EDITOR_SCOPE = 'source-editor'
const RECORD_SCOPE = 'source-editor-record'
let previousScope = 'all'
let previousFilter: ((event: KeyboardEvent) => boolean) | null = null

const bindHotKeys = () => {
  hotkeys.deleteScope(EDITOR_SCOPE)
  shortcutActions.value.forEach(({ hotKeys, action }) => {
    if (hotKeys.length === 0) return
    hotkeys(hotKeys.join('+'), EDITOR_SCOPE, event => {
      event.preventDefault()
      action.call(null)
    })
  })
  hotkeys.setScope(EDITOR_SCOPE)
}

function readHotkeysConfig() {
  try {
    const raw = localStorage.getItem('legado_web_editor_hotkeys')
    if (!raw) return false
    const config = JSON.parse(raw)
    if (!Array.isArray(config) || config.length === 0) return false
    shortcutActions.value.forEach((btn, index) => {
      if (config[index]) btn.hotKeys = config[index]
    })
    return true
  } catch {
    localStorage.removeItem('legado_web_editor_hotkeys')
  }
  return false
}

watch(hotkeysDialogVisible, visible => {
  if (!visible) {
    hotkeys.deleteScope(RECORD_SCOPE)
    bindHotKeys()
    return
  }
  hotkeys.deleteScope(RECORD_SCOPE)
  hotkeys('*', RECORD_SCOPE, event => {
    event.preventDefault()
    const pressedKeys = hotkeys.getPressedKeyString()
    if (pressedKeys.length === 1 && pressedKeys[0] === 'esc') {
      stopRecordKeyDown()
      return
    }
    if (recordKeyDowning.value && recordKeyDownIndex.value > -1) {
      shortcutActions.value[recordKeyDownIndex.value].hotKeys = pressedKeys
    }
  })
  hotkeys.setScope(RECORD_SCOPE)
})

onMounted(() => {
  previousScope = hotkeys.getScope()
  previousFilter = hotkeys.filter
  hotkeys.filter = (event: KeyboardEvent) => {
    const curScope = hotkeys.getScope()
    if (curScope === EDITOR_SCOPE || curScope === RECORD_SCOPE) {
      return true
    }
    return previousFilter ? previousFilter(event) : true
  }
  readHotkeysConfig()
  bindHotKeys()
})

onUnmounted(() => {
  hotkeys.deleteScope(EDITOR_SCOPE)
  hotkeys.deleteScope(RECORD_SCOPE)
  if (previousFilter) {
    hotkeys.filter = previousFilter
  }
  hotkeys.setScope(previousScope || 'all')
})
</script>

<style lang="scss" scoped>
.source-editor-page {
  display: flex;
  flex-direction: column;
  height: 100vh;
  width: 100vw;
  overflow: hidden;
  background: #f8fafc;
  color: #1e293b;

  &.night {
    background: #18191a;
    color: #e2e8f0;

    .editor-topbar {
      background: #242526;
      border-bottom-color: #333;

      .topbar-btn {
        color: #aaa;
        &:hover {
          background: rgba(255, 255, 255, 0.08);
        }
      }

      .topbar-title {
        color: #e2e8f0;
      }

      .action-btn {
        background: #2b2c2e;
        border-color: #3a3b3d;
        color: #cbd5e1;

        &:hover {
          background: #334155;
          color: #fff;
        }

        &.active {
          background: rgba(59, 130, 246, 0.2);
          border-color: #3b82f6;
          color: #93c5fd;
        }

        &.primary {
          background: var(--web-primary, #1e80ff);
          border-color: var(--web-primary, #1e80ff);
          color: #fff;
          &:hover {
            opacity: 0.9;
          }
        }
      }
    }

    .editor-content {
      .left-panel {
        background: #1e1f20;
      }
      .right-panel {
        background: #18191a;
        border-left-color: #333;
        border-top-color: #333;
      }
    }

    .hotkeys-modal {
      background: #242526;
      color: #e2e8f0;

      .web-dialog__header {
        border-bottom-color: #333;
      }

      .hotkeys-settings .hotkeys-item {
        border-bottom-color: #333;
        .hotkeys-keys kbd {
          background: #1e1f20;
          border-color: #3a3b3d;
          color: #93c5fd;
        }
      }
    }
  }
}

.editor-topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 52px;
  padding: 0 16px;
  background: #fff;
  border-bottom: 1px solid #e2e8f0;
  flex-shrink: 0;
  z-index: 10;

  .topbar-left {
    display: flex;
    align-items: center;
    gap: 10px;
    min-width: 0;

    .topbar-btn {
      display: flex;
      align-items: center;
      justify-content: center;
      width: 34px;
      height: 34px;
      border-radius: 6px;
      border: none;
      background: transparent;
      color: #475569;
      cursor: pointer;
      transition: all 0.15s;

      &:hover {
        background: #f1f5f9;
      }

      svg {
        width: 20px;
        height: 20px;
      }
    }

    .topbar-title {
      font-size: 16px;
      font-weight: 600;
      margin: 0;
      white-space: nowrap;
    }
  }

  .topbar-right {
    display: flex;
    align-items: center;
    gap: 8px;

    .action-btn {
      display: inline-flex;
      align-items: center;
      gap: 5px;
      height: 32px;
      padding: 0 12px;
      border-radius: 6px;
      border: 1px solid #cbd5e1;
      background: #fff;
      color: #334155;
      font-size: 13px;
      font-weight: 500;
      cursor: pointer;
      transition: all 0.15s;

      svg {
        width: 15px;
        height: 15px;
      }

      &:hover {
        background: #f8fafc;
        border-color: #94a3b8;
      }

      &.active {
        background: rgba(30, 128, 255, 0.1);
        border-color: var(--web-primary, #1e80ff);
        color: var(--web-primary, #1e80ff);
      }

      &.primary {
        background: var(--web-primary, #1e80ff);
        border-color: var(--web-primary, #1e80ff);
        color: #fff;

        &:hover {
          background: #186bd9;
          border-color: #186bd9;
        }
      }

      &.secondary {
        background: #f8fafc;
      }
    }
  }
}

.editor-content {
  display: flex;
  flex: 1;
  overflow: hidden;

  .left-panel {
    flex: 1;
    display: flex;
    flex-direction: column;
    padding: 12px 18px;
    min-width: 0;
    overflow-y: auto;
    background: #fff;
  }

  .right-panel {
    flex: 1;
    display: flex;
    flex-direction: column;
    padding: 12px 16px;
    border-left: 1px solid #e2e8f0;
    min-height: 0;
    min-width: 0;
    overflow-y: auto;
    background: #fafbfc;
  }

  @media (max-width: 768px) {
    flex-direction: column;

    .right-panel {
      border-left: none;
      border-top: 1px solid #e2e8f0;
    }
  }
}

/* 快捷键弹窗 */
.hotkeys-modal {
  width: 100%;
  max-width: 440px;

  .dialog-header-actions {
    display: flex;
    align-items: center;
    gap: 8px;
  }

  .hotkeys-settings {
    display: flex;
    flex-direction: column;
    padding: 8px 0;

    .hotkeys-item {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 10px 0;
      border-bottom: 1px solid #f1f5f9;

      .action-name {
        font-size: 14px;
        font-weight: 500;
      }

      .hotkeys-keys {
        display: flex;
        align-items: center;
        gap: 4px;

        kbd {
          padding: 2px 6px;
          border-radius: 4px;
          background: #f1f5f9;
          border: 1px solid #cbd5e1;
          font-family: inherit;
          font-size: 12px;
          font-weight: 600;
          color: #1e293b;
        }

        .plus {
          color: #94a3b8;
          font-size: 12px;
        }

        .empty-tip {
          font-size: 12px;
          color: #94a3b8;
        }
      }

      .edit-btn {
        padding: 2px 8px;
        font-size: 13px;
        color: var(--web-primary, #1e80ff);
        cursor: pointer;
      }
    }
  }
}
</style>
