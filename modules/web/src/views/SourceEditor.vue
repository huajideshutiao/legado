<template>
  <div class="editor">
    <div class="left">
      <source-tab-form :config="config" />
    </div>
    <tool-bar class="toolbar" />
    <div class="right">
      <source-tab-tools />
    </div>
  </div>
</template>
<script setup lang="ts">
import bookSourceConfig from '@/config/bookSourceEditConfig'
import '@/assets/sourceeditor.css'
import type { SourceConfig } from '@/config/sourceConfig'

const config: SourceConfig = bookSourceConfig as SourceConfig
document.title = '书源管理'
</script>
<style lang="scss" scoped>
.editor {
  display: flex;
  height: 100vh;
  overflow: hidden;

  .left {
    flex: 1;
    display: flex;
    flex-direction: column;
    padding: 12px 16px;
    min-width: 0;
    min-height: 0;
  }

  .toolbar {
    flex-shrink: 0;
  }

  .right {
    flex: 0 0 380px;
    display: flex;
    flex-direction: column;
    padding: 12px 16px;
    border-left: 1px solid var(--web-border);
    min-height: 0;
  }
}
</style>
