<template>
  <input
    class="web-input search"
    v-model="searchKey"
    placeholder="筛选源"
  />
  <div class="tool">
    <button class="web-btn" @click="importSourceFile">打开</button>
    <button class="web-btn" :disabled="sourcesFiltered.length === 0" @click="outExport">导出</button>
    <button class="web-btn web-btn--danger" :disabled="sourceSelect.length === 0" @click="deleteSelectSources">删除</button>
    <button class="web-btn web-btn--danger" :disabled="sources.length === 0" @click="clearAllSources">清空</button>
  </div>
  <div id="source-list">
    <virtual-list
      style="height: calc(100vh - 112px - 7px); overflow-y: auto; overflow-x: hidden"
      :data-key="(source: Source) => getSourceName(source)"
      :data-sources="sourcesFiltered"
      :data-component="SourceItem"
      :estimate-size="45"
      :extra-props="{ modelValue: sourceUrlSelect }"
    />
  </div>
</template>

<script setup lang="ts">
import API from '@api'
import {
  isSourceMatches,
  getSourceUniqueKey,
  getSourceName,
  convertSourcesToMap,
} from '@utils/souce'
import VirtualList from 'vue3-virtual-scroll-list'
import SourceItem from './SourceItem.vue'
import { toast } from '@/utils/toast'
import type { Source } from '@/source'

const store = useSourceStore()
const sourceUrlSelect = ref<string[]>([])
const searchKey = ref('')
const sources = computed(() => store.sources)

const sourcesFiltered = computed<Source[]>(() => {
  const key = searchKey.value
  if (key === '') return sources.value
  return sources.value.filter(source => isSourceMatches(source, key))
})
const sourceSelect = computed<Source[]>(() => {
  const urls = sourceUrlSelect.value
  if (urls.length == 0) return []
  const sourcesFilteredMap =
    searchKey.value == ''
      ? store.sourcesMap
      : convertSourcesToMap(sourcesFiltered.value)
  return urls.reduce((sources, sourceUrl) => {
    const source = sourcesFilteredMap.get(sourceUrl)
    if (source) sources.push(source)
    return sources
  }, [] as Source[])
})

const deleteSelectSources = () => {
  const sourceSelectValue = sourceSelect.value
  API.deleteSource(sourceSelectValue).then(({ data }) => {
    if (!data.isSuccess) return toast.error(data.errorMsg)
    store.deleteSources(sourceSelectValue)
    const sourceUrlSelectRawValue = toRaw(sourceUrlSelect.value)
    sourceSelectValue.forEach(source => {
      const index = sourceUrlSelectRawValue.indexOf(getSourceUniqueKey(source))
      if (index > -1) sourceUrlSelectRawValue.splice(index, 1)
    })
    sourceUrlSelect.value = sourceUrlSelectRawValue
  })
}
const clearAllSources = () => {
  store.clearAllSource()
  sourceUrlSelect.value = []
}

const importSourceFile = () => {
  const input = document.createElement('input')
  input.type = 'file'
  input.accept = '.json,.txt'
  input.addEventListener('change', () => {
    const files = input.files
    if (files === null) {
      return toast.info('未选择文件')
    }
    const reader = new FileReader()
    reader.readAsText(files[0])
    reader.onload = () => {
      try {
        const jsonData = JSON.parse(reader.result as string)
        store.saveSources(jsonData)
      } catch (e: unknown) {
        toast.error('上传的源格式错误: ' + (e as Error).message)
      }
    }
  })
  input.click()
}

const outExport = () => {
  const exportFile = document.createElement('a')
  const sources =
      sourceUrlSelect.value.length === 0
        ? sourcesFiltered.value
        : sourceSelect.value

  exportFile.download = `BookSource_${Date()
    .replace(/.*?\s(\d+)\s(\d+)\s(\d+:\d+:\d+).*/, '$2$1$3')
    .replace(/:/g, '')}.json`

  const myBlob = new Blob([JSON.stringify(sources, null, 4)], {
    type: 'application/json',
  })
  exportFile.href = window.URL.createObjectURL(myBlob)
  exportFile.click()
  window.URL.revokeObjectURL(exportFile.href)
}
</script>

<style lang="scss" scoped>
.tool {
  display: flex;
  gap: 6px;
  margin: 4px 0;
  justify-content: center;
}
.search {
  margin-bottom: 0;
}
</style>
