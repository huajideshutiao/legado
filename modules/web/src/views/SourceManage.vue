<template>
  <div :class="{ 'source-manage-page': true, night: isNight, day: !isNight }">
    <!-- 顶栏 -->
    <header class="page-topbar">
      <button class="topbar-btn back-btn" type="button" aria-label="返回" @click="goBack">
        <svg viewBox="0 0 24 24" fill="currentColor">
          <path d="M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z" />
        </svg>
      </button>

      <div class="topbar-title-wrap">
        <span class="topbar-title">书源管理</span>
        <span class="topbar-stats">
          ({{ enabledCount }}/{{ sources.length }})
        </span>
      </div>

      <div class="topbar-actions">
        <button class="action-btn" type="button" title="新建书源" @click="createSource">
          <svg viewBox="0 0 24 24" fill="currentColor">
            <path d="M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z" />
          </svg>
        </button>
        <button class="action-btn" type="button" title="导入书源" @click="showImportModal = true">
          <svg viewBox="0 0 24 24" fill="currentColor">
            <path d="M19.35 10.04C18.67 6.59 15.64 4 12 4 9.11 4 6.6 5.64 5.35 8.04 2.34 8.36 0 10.91 0 14c0 3.31 2.69 6 6 6h13c2.76 0 5-2.24 5-5 0-2.64-2.05-4.78-4.65-4.96zM14 13v4h-4v-4H7l5-5 5 5h-3z" />
          </svg>
        </button>
        <button class="action-btn" type="button" title="导出书源" @click="exportSources">
          <svg viewBox="0 0 24 24" fill="currentColor">
            <path d="M19.35 10.04C18.67 6.59 15.64 4 12 4 9.11 4 6.6 5.64 5.35 8.04 2.34 8.36 0 10.91 0 14c0 3.31 2.69 6 6 6h13c2.76 0 5-2.24 5-5 0-2.64-2.05-4.78-4.65-4.96zM17 13l-5 5-5-5h3V9h4v4h3z" />
          </svg>
        </button>
      </div>
    </header>

    <!-- 筛选控制工具栏 -->
    <div class="toolbar">
      <div class="search-wrap">
        <svg class="search-icon" viewBox="0 0 24 24" fill="currentColor">
          <path
            d="M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3S3 5.91 3 9.5 5.91 16 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z"
          />
        </svg>
        <input
          v-model="searchKey"
          type="text"
          class="search-input"
          placeholder="搜索源名称、地址或分组"
        />
        <button v-if="searchKey" class="clear-btn" type="button" @click="searchKey = ''">×</button>
      </div>

      <div class="filters-wrap">
        <select v-model="groupFilter" class="filter-select">
          <option value="">全部分组</option>
          <option v-for="g in groups" :key="g" :value="g">{{ g }}</option>
        </select>

        <select v-model="statusFilter" class="filter-select">
          <option value="all">全部状态</option>
          <option value="enabled">仅已启用</option>
          <option value="disabled">仅已禁用</option>
        </select>
      </div>
    </div>

    <!-- 批量操作栏 -->
    <div class="batch-bar">
      <label class="select-all-label">
        <input
          type="checkbox"
          :checked="isAllSelected"
          :indeterminate="isIndeterminate"
          @change="toggleSelectAll"
        />
        <span>全选 ({{ selectedKeys.size }})</span>
      </label>

      <div class="batch-buttons" v-if="selectedKeys.size > 0">
        <button class="batch-btn" type="button" @click="batchToggleEnabled(true)">启用</button>
        <button class="batch-btn" type="button" @click="batchToggleEnabled(false)">禁用</button>
        <button class="batch-btn danger" type="button" @click="batchDelete">删除</button>
      </div>
    </div>

    <!-- 书源列表 -->
    <div class="source-list-scroll" ref="listWrapperRef">
      <div v-if="filteredSources.length > 0" class="source-list">
        <div
          v-for="source in filteredSources"
          :key="source.bookSourceUrl"
          class="source-card"
          :class="{ disabled: source.enabled === false, selected: selectedKeys.has(source.bookSourceUrl) }"
        >
          <div class="card-left">
            <input
              type="checkbox"
              class="item-checkbox"
              :checked="selectedKeys.has(source.bookSourceUrl)"
              @change="toggleSelect(source.bookSourceUrl)"
            />
          </div>

          <div class="card-center" @click="editSource(source)">
            <div class="source-header-row">
              <span class="source-name">{{ source.bookSourceName || source.bookSourceUrl }}</span>
              <span class="type-tag">{{ typeLabel(source.bookSourceType) }}</span>
              <span v-if="source.exploreUrl" class="explore-tag">有发现</span>
            </div>
            <div class="source-url">{{ source.bookSourceUrl }}</div>
            <div class="source-tags-row">
              <span v-if="source.bookSourceGroup" class="group-tag">{{ source.bookSourceGroup }}</span>
              <span v-if="source.weight !== undefined" class="weight-tag">权重: {{ source.weight }}</span>
              <span v-if="source.customOrder !== undefined" class="order-tag">排序: {{ source.customOrder }}</span>
            </div>
          </div>

          <div class="card-right">
            <!-- 启用/禁用 开关 -->
            <label class="switch" title="启停书源">
              <input
                type="checkbox"
                :checked="source.enabled !== false"
                @change="toggleSourceEnabled(source)"
              />
              <span class="slider round"></span>
            </label>

            <!-- 操作按钮 -->
            <button
              class="icon-btn edit-btn"
              type="button"
              title="编辑书源"
              @click.stop="editSource(source)"
            >
              ✎
            </button>
            <button
              class="icon-btn delete-btn"
              type="button"
              title="删除书源"
              @click.stop="deleteSingleSource(source)"
            >
              🗑
            </button>
          </div>
        </div>
      </div>

      <div v-else-if="!isLoading" class="empty-state">
        <div class="empty-text">
          {{ sources.length > 0 ? '无匹配书源' : '暂无书源，请点击右上角导入或新建' }}
        </div>
      </div>
    </div>

    <!-- 导入书源弹窗 -->
    <div v-if="showImportModal" class="modal-mask" @click="showImportModal = false">
      <div class="modal-content" @click.stop>
        <div class="modal-title">导入书源</div>
        <div class="tabs-nav">
          <button
            class="tab-btn"
            :class="{ active: importTab === 'network' }"
            @click="importTab = 'network'"
          >
            网络链接导入
          </button>
          <button
            class="tab-btn"
            :class="{ active: importTab === 'text' }"
            @click="importTab = 'text'"
          >
            文本直接导入
          </button>
          <button
            class="tab-btn"
            :class="{ active: importTab === 'file' }"
            @click="importTab = 'file'"
          >
            本地文件导入
          </button>
        </div>

        <div class="tab-body" v-if="importTab === 'network'">
          <input
            v-model="importUrl"
            type="text"
            class="modal-input"
            placeholder="输入书源网络 URL (http:// 或 https://)"
          />
          <div class="input-hint">支持单个或合集书源订阅链接</div>
        </div>

        <div class="tab-body" v-else-if="importTab === 'text'">
          <textarea
            v-model="importText"
            class="modal-textarea"
            rows="6"
            placeholder="粘贴书源 JSON 文本 (单个对象或数组)"
          ></textarea>
        </div>

        <div class="tab-body file-tab" v-else-if="importTab === 'file'">
          <input
            ref="fileInputRef"
            type="file"
            accept=".json,.txt"
            hidden
            @change="onFileSelected"
          />
          <button class="file-choose-btn" type="button" @click="fileInputRef?.click()">
            选择本地 .json / .txt 书源文件
          </button>
          <div v-if="selectedFileName" class="selected-file-name">
            已选择：{{ selectedFileName }} ({{ fileParsedSources.length }} 个书源)
          </div>
        </div>

        <div class="modal-actions">
          <button class="modal-btn secondary" type="button" @click="showImportModal = false">
            取消
          </button>
          <button
            class="modal-btn primary"
            type="button"
            :disabled="isImporting"
            @click="executeImport"
          >
            {{ isImporting ? '导入中...' : '确认导入' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { useBookStore } from '@/store'
import { useSourceStore } from '@/store/sourceStore'
import { useLoading } from '@/hooks/loading'
import API from '@api'
import type { BookSource, Source } from '@/source'
import { toast } from '@/utils/toast'

const router = useRouter()
const store = useBookStore()
const sourceStore = useSourceStore()
const isNight = computed(() => store.isNight)

const sources = ref<BookSource[]>([])
const selectedKeys = ref<Set<string>>(new Set())
const listWrapperRef = ref<HTMLElement>()

const searchKey = ref('')
const groupFilter = ref('')
const statusFilter = ref<'all' | 'enabled' | 'disabled'>('all')

const { showLoading, closeLoading, loadingWrapper, isLoading } = useLoading(
  listWrapperRef,
  '正在加载书源列表',
)

// 弹窗状态
const showImportModal = ref(false)
const importTab = ref<'network' | 'text' | 'file'>('network')
const importUrl = ref('')
const importText = ref('')
const isImporting = ref(false)
const fileInputRef = ref<HTMLInputElement>()
const selectedFileName = ref('')
const fileParsedSources = ref<BookSource[]>([])

const enabledCount = computed(() => sources.value.filter(s => s.enabled !== false).length)

const groups = computed(() => {
  const set = new Set<string>()
  for (const s of sources.value) {
    if (s.bookSourceGroup) {
      s.bookSourceGroup
        .split(/[,，]/)
        .map(x => x.trim())
        .filter(Boolean)
        .forEach(g => set.add(g))
    }
  }
  return Array.from(set)
})

const filteredSources = computed(() => {
  let list = sources.value

  // 分组筛选
  if (groupFilter.value) {
    list = list.filter(s =>
      (s.bookSourceGroup ?? '')
        .split(/[,，]/)
        .map(x => x.trim())
        .includes(groupFilter.value),
    )
  }

  // 启停状态筛选
  if (statusFilter.value === 'enabled') {
    list = list.filter(s => s.enabled !== false)
  } else if (statusFilter.value === 'disabled') {
    list = list.filter(s => s.enabled === false)
  }

  // 关键词筛选
  const key = searchKey.value.trim().toLowerCase()
  if (!key) return list

  return list.filter(
    s =>
      (s.bookSourceName ?? '').toLowerCase().includes(key) ||
      (s.bookSourceUrl ?? '').toLowerCase().includes(key) ||
      (s.bookSourceGroup ?? '').toLowerCase().includes(key),
  )
})

const isAllSelected = computed(() => {
  if (filteredSources.value.length === 0) return false
  return filteredSources.value.every(s => selectedKeys.value.has(s.bookSourceUrl))
})

const isIndeterminate = computed(() => {
  const count = filteredSources.value.filter(s => selectedKeys.value.has(s.bookSourceUrl)).length
  return count > 0 && count < filteredSources.value.length
})

const toggleSelectAll = () => {
  if (isAllSelected.value) {
    for (const s of filteredSources.value) {
      selectedKeys.value.delete(s.bookSourceUrl)
    }
  } else {
    for (const s of filteredSources.value) {
      selectedKeys.value.add(s.bookSourceUrl)
    }
  }
}

const toggleSelect = (url: string) => {
  if (selectedKeys.value.has(url)) {
    selectedKeys.value.delete(url)
  } else {
    selectedKeys.value.add(url)
  }
}

const typeLabel = (type?: number) =>
  type === 1 ? '音频' : type === 2 ? '图片' : type === 3 ? '文件' : '文本'

const goBack = () => {
  if (window.history.length > 1) {
    router.back()
  } else {
    router.push('/my')
  }
}

const loadSources = async () => {
  showLoading()
  try {
    const { data } = await API.getSources()
    if (data.isSuccess) {
      sources.value = (data.data as BookSource[]) || []
    } else if (data.data == null) {
      sources.value = []
    } else {
      toast.error(data.errorMsg || '加载书源失败')
    }
  } catch (e) {
    toast.error((e as Error)?.message || '网络异常')
  } finally {
    closeLoading()
  }
}

const toggleSourceEnabled = async (source: BookSource) => {
  const prev = source.enabled !== false
  source.enabled = !prev
  try {
    const resp = await API.saveSource(source as unknown as Source)
    if (resp.data.isSuccess) {
      toast.success(source.enabled ? '已启用书源' : '已禁用书源')
    } else {
      source.enabled = prev
      toast.error(resp.data.errorMsg || '更新失败')
    }
  } catch (e) {
    source.enabled = prev
    toast.error((e as Error)?.message || '网络异常')
  }
}

const batchToggleEnabled = async (enabled: boolean) => {
  const targets = sources.value.filter(s => selectedKeys.value.has(s.bookSourceUrl))
  if (targets.length === 0) return
  const prevStates = new Map<string, boolean>()
  targets.forEach(s => {
    prevStates.set(s.bookSourceUrl, s.enabled)
    s.enabled = enabled
  })
  try {
    const resp = await API.saveSources(targets as unknown as Source[])
    if (resp.data.isSuccess) {
      toast.success(`批量${enabled ? '启用' : '禁用'} ${targets.length} 个书源`)
    } else {
      targets.forEach(s => {
        const prev = prevStates.get(s.bookSourceUrl)
        if (prev !== undefined) s.enabled = prev
      })
      toast.error(resp.data.errorMsg || '批量操作失败')
    }
  } catch (e) {
    targets.forEach(s => {
      const prev = prevStates.get(s.bookSourceUrl)
      if (prev !== undefined) s.enabled = prev
    })
    toast.error((e as Error)?.message || '操作异常')
  }
}

const batchDelete = async () => {
  const targets = sources.value.filter(s => selectedKeys.value.has(s.bookSourceUrl))
  if (targets.length === 0) return
  if (!confirm(`确定要删除选中的 ${targets.length} 个书源吗？`)) return

  try {
    const resp = await API.deleteSource(targets as unknown as Source[])
    if (resp.data.isSuccess) {
      toast.success('删除成功')
      sources.value = sources.value.filter(s => !selectedKeys.value.has(s.bookSourceUrl))
      selectedKeys.value.clear()
    } else {
      toast.error(resp.data.errorMsg || '删除失败')
    }
  } catch (e) {
    toast.error((e as Error)?.message || '删除异常')
  }
}

const deleteSingleSource = async (source: BookSource) => {
  if (!confirm(`确定删除书源《${source.bookSourceName}》？`)) return
  try {
    const resp = await API.deleteSource([source] as unknown as Source[])
    if (resp.data.isSuccess) {
      toast.success('已删除')
      sources.value = sources.value.filter(s => s.bookSourceUrl !== source.bookSourceUrl)
      selectedKeys.value.delete(source.bookSourceUrl)
    } else {
      toast.error(resp.data.errorMsg || '删除失败')
    }
  } catch (e) {
    toast.error((e as Error)?.message || '网络异常')
  }
}

const createSource = () => {
  sourceStore.clearEdit()
  router.push('/bookSource')
}

const editSource = (source: BookSource) => {
  sourceStore.changeCurrentSource(source as unknown as Source)
  router.push('/bookSource')
}

const exportSources = () => {
  const targets =
    selectedKeys.value.size > 0
      ? sources.value.filter(s => selectedKeys.value.has(s.bookSourceUrl))
      : filteredSources.value

  if (targets.length === 0) {
    return toast.info('没有可导出的书源')
  }

  const exportFile = document.createElement('a')
  exportFile.download = `BookSources_${new Date().toISOString().slice(0, 10)}.json`
  const blob = new Blob([JSON.stringify(targets, null, 2)], {
    type: 'application/json',
  })
  exportFile.href = URL.createObjectURL(blob)
  exportFile.click()
  URL.revokeObjectURL(exportFile.href)
  toast.success(`已导出 ${targets.length} 个书源`)
}

const onFileSelected = (evt: Event) => {
  const input = evt.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  selectedFileName.value = file.name
  const reader = new FileReader()
  reader.readAsText(file)
  reader.onload = () => {
    try {
      const parsed = JSON.parse(reader.result as string)
      fileParsedSources.value = Array.isArray(parsed) ? parsed : [parsed]
      toast.success(`已解析 ${fileParsedSources.value.length} 个书源`)
    } catch (e) {
      toast.error('文件格式错误: 不是有效的 JSON')
      fileParsedSources.value = []
    }
  }
}

const executeImport = async () => {
  isImporting.value = true
  try {
    let toSave: BookSource[] = []

    if (importTab.value === 'network') {
      const url = importUrl.value.trim()
      if (!url) return toast.info('请输入书源链接')
      const resp = await fetch(url)
      if (!resp.ok) throw new Error(`拉取失败 HTTP ${resp.status}`)
      const json = await resp.json()
      toSave = Array.isArray(json) ? json : [json]
    } else if (importTab.value === 'text') {
      const text = importText.value.trim()
      if (!text) return toast.info('请输入书源内容')
      const parsed = JSON.parse(text)
      toSave = Array.isArray(parsed) ? parsed : [parsed]
    } else if (importTab.value === 'file') {
      if (fileParsedSources.value.length === 0) return toast.info('请选择有效的书源文件')
      toSave = fileParsedSources.value
    }

    if (toSave.length === 0) {
      return toast.error('未解析到任何书源数据')
    }

    const saveResp = await API.saveSources(toSave as unknown as Source[])
    if (saveResp.data.isSuccess) {
      toast.success(`成功导入 ${toSave.length} 个书源`)
      showImportModal.value = false
      importUrl.value = ''
      importText.value = ''
      selectedFileName.value = ''
      fileParsedSources.value = []
      await loadSources()
    } else {
      toast.error(saveResp.data.errorMsg || '导入失败')
    }
  } catch (e) {
    toast.error(`导入错误: ${(e as Error).message}`)
  } finally {
    isImporting.value = false
  }
}

onMounted(() => {
  loadingWrapper(loadSources())
})
</script>

<style lang="scss" scoped>
.source-manage-page {
  height: 100vh;
  width: 100vw;
  display: flex;
  flex-direction: column;
  background-color: #f7f7f7;
  overflow: hidden;
  color: var(--web-text, #333);

  .page-topbar {
    flex: none;
    height: 52px;
    display: flex;
    align-items: center;
    padding: 0 12px;
    background: #fff;
    border-bottom: 1px solid rgba(128, 128, 128, 0.15);
    gap: 8px;

    .topbar-btn {
      width: 36px;
      height: 36px;
      border: none;
      background: transparent;
      display: flex;
      align-items: center;
      justify-content: center;
      cursor: pointer;
      color: #666;
      padding: 0;

      svg {
        width: 22px;
        height: 22px;
      }
    }

    .topbar-title-wrap {
      flex: 1;
      display: flex;
      align-items: baseline;
      gap: 6px;

      .topbar-title {
        font-size: 17px;
        font-weight: 700;
      }

      .topbar-stats {
        font-size: 12px;
        color: #888;
      }
    }

    .topbar-actions {
      display: flex;
      gap: 4px;

      .action-btn {
        width: 36px;
        height: 36px;
        border: none;
        background: transparent;
        display: flex;
        align-items: center;
        justify-content: center;
        cursor: pointer;
        color: #555;
        border-radius: 6px;

        &:hover {
          background: rgba(0, 0, 0, 0.05);
        }

        svg {
          width: 20px;
          height: 20px;
        }
      }
    }
  }

  .toolbar {
    flex: none;
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    padding: 8px 12px;
    background: #fff;
    border-bottom: 1px solid rgba(0, 0, 0, 0.05);

    .search-wrap {
      flex: 1;
      min-width: 200px;
      height: 32px;
      background: #f0f2f5;
      border-radius: 16px;
      display: flex;
      align-items: center;
      padding: 0 10px;
      gap: 6px;

      .search-icon {
        width: 15px;
        height: 15px;
        color: #999;
      }

      .search-input {
        flex: 1;
        border: none;
        outline: none;
        background: transparent;
        font-size: 13px;
        color: inherit;
      }

      .clear-btn {
        border: none;
        background: transparent;
        cursor: pointer;
        color: #999;
        font-size: 16px;
        line-height: 1;
      }
    }

    .filters-wrap {
      display: flex;
      gap: 6px;

      .filter-select {
        height: 32px;
        padding: 0 8px;
        border-radius: 6px;
        border: 1px solid #ddd;
        background: #fff;
        font-size: 12px;
        color: #555;
        outline: none;
      }
    }
  }

  .batch-bar {
    flex: none;
    height: 38px;
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 0 14px;
    background: #fff;
    border-bottom: 1px solid rgba(0, 0, 0, 0.05);
    font-size: 13px;

    .select-all-label {
      display: flex;
      align-items: center;
      gap: 6px;
      cursor: pointer;
      user-select: none;
    }

    .batch-buttons {
      display: flex;
      gap: 6px;

      .batch-btn {
        border: 1px solid #ddd;
        background: #fff;
        padding: 2px 10px;
        border-radius: 4px;
        font-size: 12px;
        cursor: pointer;

        &:hover {
          border-color: var(--web-primary, #1e80ff);
          color: var(--web-primary, #1e80ff);
        }

        &.danger {
          border-color: #ff4d4f;
          color: #ff4d4f;

          &:hover {
            background: #fff1f0;
          }
        }
      }
    }
  }

  .source-list-scroll {
    flex: 1;
    overflow-y: auto;
    padding: 10px 12px 40px;

    .source-list {
      max-width: 900px;
      margin: 0 auto;
      display: flex;
      flex-direction: column;
      gap: 8px;

      .source-card {
        display: flex;
        align-items: center;
        background: #fff;
        border-radius: 8px;
        padding: 10px 12px;
        box-shadow: 0 1px 3px rgba(0, 0, 0, 0.03);
        transition: all 0.2s;

        &.disabled {
          opacity: 0.65;
        }

        &.selected {
          border-left: 3px solid var(--web-primary, #1e80ff);
        }

        .card-left {
          margin-right: 12px;
          display: flex;
          align-items: center;

          .item-checkbox {
            width: 16px;
            height: 16px;
            cursor: pointer;
          }
        }

        .card-center {
          flex: 1;
          min-width: 0;
          cursor: pointer;

          .source-header-row {
            display: flex;
            align-items: center;
            gap: 6px;

            .source-name {
              font-size: 14px;
              font-weight: 600;
              color: #222;
              white-space: nowrap;
              overflow: hidden;
              text-overflow: ellipsis;
            }

            .type-tag {
              font-size: 10px;
              background: #ecfdf5;
              color: #059669;
              padding: 1px 5px;
              border-radius: 3px;
              white-space: nowrap;
            }

            .explore-tag {
              font-size: 10px;
              background: #eef2ff;
              color: #4f46e5;
              padding: 1px 5px;
              border-radius: 3px;
              white-space: nowrap;
            }
          }

          .source-url {
            font-size: 11px;
            color: #888;
            white-space: nowrap;
            overflow: hidden;
            text-overflow: ellipsis;
            margin-top: 2px;
          }

          .source-tags-row {
            display: flex;
            gap: 6px;
            margin-top: 4px;
            font-size: 11px;
            color: #777;

            .group-tag {
              background: #f3f4f6;
              padding: 0 5px;
              border-radius: 3px;
            }

            .weight-tag,
            .order-tag {
              color: #999;
            }
          }
        }

        .card-right {
          display: flex;
          align-items: center;
          gap: 10px;
          margin-left: 10px;

          .switch {
            position: relative;
            display: inline-block;
            width: 36px;
            height: 20px;

            input {
              opacity: 0;
              width: 0;
              height: 0;
            }

            .slider {
              position: absolute;
              cursor: pointer;
              top: 0;
              left: 0;
              right: 0;
              bottom: 0;
              background-color: #ccc;
              transition: 0.2s;

              &.round {
                border-radius: 20px;
              }

              &.round:before {
                border-radius: 50%;
              }

              &:before {
                position: absolute;
                content: '';
                height: 14px;
                width: 14px;
                left: 3px;
                bottom: 3px;
                background-color: white;
                transition: 0.2s;
              }
            }

            input:checked + .slider {
              background-color: var(--web-primary, #1e80ff);
            }

            input:checked + .slider:before {
              transform: translateX(16px);
            }
          }

          .icon-btn {
            border: none;
            background: transparent;
            font-size: 16px;
            cursor: pointer;
            color: #888;
            padding: 4px;
            border-radius: 4px;

            &:hover {
              background: rgba(0, 0, 0, 0.05);
              color: #333;
            }

            &.delete-btn:hover {
              color: #ff4d4f;
            }
          }
        }
      }
    }

    .empty-state {
      text-align: center;
      padding: 60px 0;
      color: #999;
      font-size: 14px;
    }
  }

  .modal-mask {
    position: fixed;
    inset: 0;
    z-index: 100;
    background: rgba(0, 0, 0, 0.45);
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 20px;

    .modal-content {
      width: 100%;
      max-width: 520px;
      background: #fff;
      border-radius: 12px;
      padding: 20px;
      box-shadow: 0 10px 25px rgba(0, 0, 0, 0.2);

      .modal-title {
        font-size: 17px;
        font-weight: 700;
        margin-bottom: 14px;
      }

      .tabs-nav {
        display: flex;
        border-bottom: 1px solid #eee;
        margin-bottom: 14px;

        .tab-btn {
          flex: 1;
          padding: 8px 0;
          border: none;
          background: transparent;
          font-size: 13px;
          cursor: pointer;
          color: #666;
          border-bottom: 2px solid transparent;

          &.active {
            color: var(--web-primary, #1e80ff);
            border-bottom-color: var(--web-primary, #1e80ff);
            font-weight: 600;
          }
        }
      }

      .tab-body {
        margin-bottom: 16px;

        .modal-input {
          width: 100%;
          height: 38px;
          border: 1px solid #ddd;
          border-radius: 6px;
          padding: 0 10px;
          font-size: 14px;
          outline: none;
          box-sizing: border-box;

          &:focus {
            border-color: var(--web-primary, #1e80ff);
          }
        }

        .input-hint {
          font-size: 12px;
          color: #999;
          margin-top: 6px;
        }

        .modal-textarea {
          width: 100%;
          border: 1px solid #ddd;
          border-radius: 6px;
          padding: 8px 10px;
          font-size: 13px;
          outline: none;
          resize: vertical;
          box-sizing: border-box;

          &:focus {
            border-color: var(--web-primary, #1e80ff);
          }
        }

        &.file-tab {
          text-align: center;
          padding: 20px 0;

          .file-choose-btn {
            border: 1px dashed var(--web-primary, #1e80ff);
            background: rgba(30, 128, 255, 0.05);
            color: var(--web-primary, #1e80ff);
            padding: 12px 24px;
            border-radius: 8px;
            cursor: pointer;
            font-size: 14px;
          }

          .selected-file-name {
            margin-top: 10px;
            font-size: 13px;
            color: #555;
          }
        }
      }

      .modal-actions {
        display: flex;
        justify-content: flex-end;
        gap: 10px;

        .modal-btn {
          height: 36px;
          padding: 0 18px;
          border-radius: 18px;
          font-size: 13px;
          cursor: pointer;
          border: none;

          &.secondary {
            background: #f0f2f5;
            color: #555;
          }

          &.primary {
            background: var(--web-primary, #1e80ff);
            color: #fff;

            &:disabled {
              opacity: 0.6;
              cursor: not-allowed;
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

  .page-topbar,
  .toolbar,
  .batch-bar {
    background: #242526;
    border-bottom-color: rgba(255, 255, 255, 0.08);

    .topbar-btn,
    .topbar-actions .action-btn {
      color: #aaa;

      &:hover {
        background: rgba(255, 255, 255, 0.08);
      }
    }
  }

  .toolbar {
    .search-wrap {
      background: #333;

      .search-input {
        color: #aeaeae;
      }
    }

    .filters-wrap .filter-select {
      background: #333;
      border-color: #444;
      color: #aeaeae;
    }
  }

  .batch-bar .batch-buttons .batch-btn {
    background: #333;
    border-color: #444;
    color: #ccc;
  }

  .source-list-scroll .source-list .source-card {
    background: #242526;
    box-shadow: none;

    .card-center {
      .source-header-row .source-name {
        color: #ddd;
      }

      .source-tags-row .group-tag {
        background: #333;
      }
    }

    .card-right .icon-btn {
      color: #aaa;

      &:hover {
        background: rgba(255, 255, 255, 0.08);
      }
    }
  }

  .modal-mask .modal-content {
    background: #242526;
    color: #aeaeae;

    .tabs-nav {
      border-bottom-color: #333;

      .tab-btn {
        color: #888;
      }
    }

    .tab-body {
      .modal-input,
      .modal-textarea {
        background: #333;
        border-color: #444;
        color: #aeaeae;
      }
    }

    .modal-actions .modal-btn.secondary {
      background: #333;
      color: #aaa;
    }
  }
}
</style>
