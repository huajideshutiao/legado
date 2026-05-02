<template>
  <div style="display:flex;flex-direction:column;gap:8px;padding:8px 0">
    <input class="web-input" placeholder="搜索" v-model="searchKey" />
    <div style="display:flex;gap:6px">
      <button class="web-btn" @click="debug">调试</button>
      <button class="web-btn" :disabled="!isDebuging" @click="stopDebug">停止</button>
    </div>
    <div ref="logContainer" style="flex:1;overflow-y:auto;font-size:12px;font-family:monospace;background:var(--web-bg);border-radius:4px;padding:8px;min-height:200px">
      <div v-for="(log, i) in logs" :key="i" :style="{color: log.color || 'inherit'}">{{ log.text }}</div>
      <div v-if="logs.length === 0 && !isDebuging" style="color:var(--web-text-secondary);text-align:center;padding:20px">点击调试开始</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import API from '@api'
import { toast } from '@/utils/toast'

const store = useSourceStore()
const searchKey = ref(store.searchKey)

interface LogEntry { text: string; color?: string }
const logs = ref<LogEntry[]>([])
const logContainer = ref<HTMLElement>()
const isDebuging = ref(false)

function addLog(text: string, color?: string) {
  logs.value.push({ text, color })
  nextTick(() => {
    if (logContainer.value) {
      logContainer.value.scrollTop = logContainer.value.scrollHeight
    }
  })
}

function debug() {
  if (!store.currentSourceUrl) {
    toast.warning('源URL为空')
    return
  }
  logs.value = []
  isDebuging.value = true
  addLog('开始调试...')
  API.debug(
    store.currentSourceUrl,
    searchKey.value,
    (data) => {
      try {
        const obj = JSON.parse(data)
        if (obj.type === 'toast') {
          addLog(obj.msg, '#e6a23c')
        } else if (obj.type === 'content') {
          addLog(obj.content || data, '#67c23a')
        } else if (obj.type === 'error') {
          addLog(obj.msg || data, '#f56c6c')
        } else {
          addLog(data)
        }
      } catch {
        addLog(data)
      }
    },
    () => {
      addLog('调试结束', '#909399')
      isDebuging.value = false
      store.debugFinish()
    },
  )
}

function stopDebug() {
  isDebuging.value = false
  store.debugFinish()
  addLog('调试已停止', '#909399')
}
</script>
