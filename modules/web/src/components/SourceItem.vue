<template>
  <label class="web-checkbox source-item" :class="{ error: isSaveError, edit: sourceUrl == currentSourceUrl }">
    <input type="checkbox" :value="sourceUrl" v-model="selected" />
    <span class="source-name">{{ getSourceName(source) }}</span>
    <button class="web-btn web-btn--text edit-btn" @click.stop="handleSourceClick(source)">✎</button>
  </label>
</template>

<script setup lang="ts">
import { getSourceUniqueKey, getSourceName } from '@/utils/souce'
import type { Source } from '@/source'

const props = defineProps<{
  source: Source
}>()

const selected = defineModel<string[]>({ default: () => [] })

const store = useSourceStore()

const currentSourceUrl = computed(() => store.currentSourceUrl)
const sourceUrl = computed(() => getSourceUniqueKey(props.source))

const handleSourceClick = (source: Source) => {
  store.changeCurrentSource(source)
}
const isSaveError = computed(() => {
  const map = store.savedSourcesMap
  if (map.size == 0) return false
  return !map.has(sourceUrl.value)
})
</script>
<style lang="scss" scoped>
.source-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  border: 1px solid var(--web-border);
  border-radius: var(--web-radius);
  margin-bottom: 4px;
  width: 100%;
  box-sizing: border-box;
}
.source-item.edit {
  border-color: var(--web-text);
}
.source-item.error {
  border-color: var(--web-danger);
  color: var(--web-danger);
}
.source-name {
  flex: 1;
  min-width: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.edit-btn {
  flex-shrink: 0;
}
</style>
