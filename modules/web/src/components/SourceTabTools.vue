<template>
  <div class="tools-panel">
    <div class="tools-tabs">
      <div class="web-tabs">
        <button
          v-for="tab in tabData"
          :key="tab[0]"
          class="web-tab"
          :class="{ 'web-tab--active': current_tab === tab[0] }"
          @click="switchTab(tab[0])"
        >
          {{ tab[1] }}
        </button>
      </div>
    </div>
    <div class="tools-body">
      <div v-show="current_tab === 'editTab'">
        <source-json />
      </div>
      <div v-show="current_tab === 'editDebug'">
        <source-debug />
      </div>
      <div v-show="current_tab === 'editList'">
        <source-list />
      </div>
      <div v-show="current_tab === 'editHelp'">
        <source-help />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">

const store = useSourceStore()
const current_tab = ref(localStorage.getItem('tabName') || 'editTab')

function switchTab(name: string) {
  current_tab.value = name
  localStorage.setItem('tabName', name)
  store.currentTab = name
}

const tabData = [
  ['editTab', '编辑源'],
  ['editDebug', '调试源'],
  ['editList', '源列表'],
  ['editHelp', '帮助信息'],
]
</script>

<style scoped>
.tools-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.tools-tabs {
  flex-shrink: 0;
}

.tools-tabs .web-tabs {
  border-bottom: 2px solid var(--web-border-light);
}

.tools-tabs .web-tab {
  background: none;
  font-size: 14px;
}

.tools-body {
  flex: 1;
  overflow-y: auto;
  padding-top: 12px;
}
</style>
