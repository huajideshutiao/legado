<template>
  <div :class="{ 'my-wrapper': true, night: isNight, day: !isNight }">
    <header class="page-topbar">
      <span class="topbar-title">我的</span>
    </header>

    <div class="my-scroll">
      <!-- 外观设置 -->
      <div class="card">
        <div class="card-title">外观设置</div>
        <div class="card-row link-row" @click="toggleTheme">
          <div class="row-content">
            <span class="row-label">深色模式</span>
            <span class="row-desc">{{ isNight ? '已开启（深色主题）' : '已关闭（浅色主题）' }}</span>
          </div>
          <div class="switch-pill" :class="{ active: isNight }">
            <div class="switch-knob"></div>
          </div>
        </div>
      </div>

      <!-- 书库与书源管理 -->
      <div class="card">
        <div class="card-title">书库与书源</div>
        
        <!-- 上传本地书 -->
        <div class="card-row link-row" @click="uploadInput?.click()">
          <span class="row-label">上传本地书籍</span>
          <span class="row-arrow">›</span>
          <input
            ref="uploadInput"
            type="file"
            accept=".txt,.epub,.zip,.cbz"
            hidden
            @change="onUploadBookFile"
          />
        </div>

        <!-- 书源管理 -->
        <div class="card-row link-row" @click="router.push('/sources')">
          <span class="row-label">书源管理</span>
          <span class="row-arrow">›</span>
        </div>
      </div>

      <!-- 备份与恢复 -->
      <div class="card">
        <div class="card-title">备份与恢复</div>
        <div class="backup-actions">
          <button
            class="backup-btn primary"
            type="button"
            :disabled="busy"
            @click="exportBackup"
          >
            导出备份
          </button>
          <button class="backup-btn" type="button" :disabled="busy" @click="importInput?.click()">
            导入恢复
          </button>
          <input
            ref="importInput"
            type="file"
            accept="application/zip,.zip"
            hidden
            @change="onImportFile"
          />
        </div>
      </div>

      <!-- 更多与帮助 -->
      <div class="card">
        <div class="card-title">更多</div>
        <a class="card-row link-row" href="./help/index.html" target="_blank">
          <span class="row-label">使用帮助</span>
          <span class="row-arrow">›</span>
        </a>
        <div class="card-row">
          <div class="row-content">
            <span class="row-label">Legado Web</span>
            <span class="row-desc">轻量化跨平台开源阅读器</span>
          </div>
          <span class="row-value">v3.0</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
defineOptions({ name: 'MyPage' })

import '@/assets/webui.css'
import { useBookStore } from '@/store'
import API from '@api'
import { toast } from '@/utils/toast'

const router = useRouter()
const store = useBookStore()
const isNight = computed(() => store.isNight)

const toggleTheme = () => {
  const nextTheme = store.isNight ? 0 : 6
  store.config.theme = nextTheme
  API.saveReadConfig(store.config)
}

const busy = ref(false)
const isUploadingBook = ref(false)
const importInput = ref<HTMLInputElement>()
const uploadInput = ref<HTMLInputElement>()
// 上传本地书籍
const onUploadBookFile = async (evt: Event) => {
  const input = evt.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return

  busy.value = true
  isUploadingBook.value = true
  const toastId = 'upload-book'
  toast.info(`正在上传并导入《${file.name}》...`)

  try {
    const resp = await API.addLocalBook(file)
    if (resp.isSuccess) {
      toast.success(`《${file.name}》导入成功，已加入书架！`)
      store.clearShelfCache()
      await store.loadBookShelf()
    } else {
      toast.error(resp.errorMsg || '上传失败')
    }
  } catch (e) {
    console.error('addLocalBook error:', e)
    toast.error((e as Error)?.message || '上传导入发生异常')
  } finally {
    busy.value = false
    isUploadingBook.value = false
  }
}

const exportBackup = async () => {
  busy.value = true
  try {
    const blob = await API.getBackupZip()
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `legado-backup-${new Date().toISOString().slice(0, 19).replace(/[:T]/g, '')}.zip`
    a.click()
    URL.revokeObjectURL(url)
    toast.success('备份已导出 (标准 zip 格式)')
  } catch (e) {
    toast.error((e as Error)?.message || '备份导出失败')
    throw e
  } finally {
    busy.value = false
  }
}

const onImportFile = async (evt: Event) => {
  const input = evt.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  busy.value = true
  try {
    const resp = await API.restoreBackup(file)
    if (resp.isSuccess) {
      toast.success('恢复完成，数据已从备份包写回')
      store.clearShelfCache()
      store.loadGroups()
      store.loadBookShelf()
    } else {
      toast.error(resp.errorMsg || '恢复失败')
    }
  } catch (e) {
    console.error('restoreBackup error:', e)
    toast.error((e as Error)?.message || '恢复失败')
  } finally {
    busy.value = false
  }
}
</script>

<style lang="scss" scoped>
.my-wrapper {
  height: 100%;
  width: 100%;
  display: flex;
  flex-direction: column;
  background-color: #f7f7f7;

  .page-topbar {
    flex: none;
    height: 52px;
    display: flex;
    align-items: center;
    padding: 0 16px;

    .topbar-title {
      font-size: 20px;
      font-weight: 600;
      color: var(--web-text);
    }
  }

  .my-scroll {
    flex: 1;
    overflow-y: auto;
    padding: 4px 16px calc(72px + env(safe-area-inset-bottom));
  }

  .card {
    margin-bottom: 14px;
    border-radius: 8px;
    background: #fff;
    padding: 12px 14px;

    .card-title {
      font-size: 14px;
      font-weight: 600;
      color: #888;
      margin-bottom: 8px;
    }

    .card-row {
      display: flex;
      align-items: center;
      gap: 12px;
      padding: 10px 4px;
      font-size: 14px;
      text-decoration: none;
      border-bottom: 1px solid #f5f5f5;

      &:last-child {
        border-bottom: none;
      }

      .row-content {
        flex: 1;
        min-width: 0;
        display: flex;
        flex-direction: column;

        .row-label {
          font-weight: 500;
          color: var(--web-text, #333);
        }

        .row-desc {
          font-size: 12px;
          color: #999;
          margin-top: 2px;
        }
      }

      .row-value {
        color: #888;
        font-size: 12px;
      }

      .row-arrow {
        font-size: 18px;
        color: #bbb;
      }

      .switch-pill {
        width: 44px;
        height: 24px;
        border-radius: 12px;
        background: #e2e8f0;
        position: relative;
        cursor: pointer;
        transition: background-color 0.2s;
        flex-shrink: 0;

        .switch-knob {
          position: absolute;
          top: 2px;
          left: 2px;
          width: 20px;
          height: 20px;
          border-radius: 50%;
          background: #fff;
          box-shadow: 0 1px 3px rgba(0, 0, 0, 0.2);
          transition: transform 0.2s;
        }

        &.active {
          background: var(--web-primary, #1e80ff);

          .switch-knob {
            transform: translateX(20px);
          }
        }
      }

      &.link-row {
        cursor: pointer;

        &:hover {
          background: rgba(0, 0, 0, 0.02);
          border-radius: 6px;
        }
      }
    }

    .backup-actions {
      display: flex;
      gap: 10px;
      padding: 6px 0 4px;

      .backup-btn {
        flex: 1;
        border: 1px solid var(--web-border);
        border-radius: 6px;
        background: #fff;
        padding: 8px 0;
        font-size: 14px;
        color: var(--web-text);
        cursor: pointer;

        &.primary {
          background: var(--web-primary);
          border-color: var(--web-primary);
          color: #fff;
        }

        &:disabled {
          opacity: 0.6;
          cursor: not-allowed;
        }
      }
    }
  }
}

.night {
  background-color: #161819;

  .page-topbar {
    .topbar-title {
      color: #aeaeae;
    }
  }

  .card {
    background: #454545;

    .card-row {
      border-bottom-color: #3e3e3e;

      .row-content .row-label {
        color: #ddd;
      }

      .switch-pill {
        background: #333;

        &.active {
          background: var(--web-primary, #1e80ff);
        }
      }

      &.link-row:hover {
        background: rgba(255, 255, 255, 0.04);
      }
    }
  }

  .card .card-row .row-value {
    color: #aeaeae;
  }

  .card .backup-actions .backup-btn {
    background: #555;
    border-color: #666;
    color: #ccc;

    &.primary {
      background: var(--web-primary);
      border-color: var(--web-primary);
      color: #fff;
    }
  }
}
</style>
