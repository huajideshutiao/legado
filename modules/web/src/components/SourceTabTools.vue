<template>
  <div class="tools-panel">
    <div class="tools-body">
      <div v-show="effectiveTab === 'editDebug'">
        <source-debug />
      </div>
      <div v-show="effectiveTab === 'editHelp'">
        <source-help />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useSourceStore } from '@/store/sourceStore'

const props = defineProps<{
  activeTab?: string | null
}>()

const store = useSourceStore()

const effectiveTab = computed(() => {
  return props.activeTab || store.currentTab || 'editDebug'
})
</script>

<style scoped>
.tools-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.tools-body {
  flex: 1;
  overflow-y: auto;
}
</style>
