<template>
  <div class="source-form">
    <div class="form-tabs">
      <div class="web-tabs">
        <button
          v-for="(tab, key) in config"
          :key="key"
          class="web-tab"
          :class="{ 'web-tab--active': activeTab === key }"
          @click="activeTab = key"
        >
          {{ tab.name }}
        </button>
      </div>
    </div>
    <div class="form-body">
      <div v-for="(tab, key) in config" :key="key" v-show="activeTab === key">
        <div v-for="field in tab.children" :key="field.id" class="web-form-group">
          <label class="web-form-label">
            {{ field.title }}
            <span v-if="field.required" class="required">*</span>
          </label>

          <textarea
            v-if="field.type === 'String' && !field.namespace"
            class="web-textarea"
            :placeholder="field.hint || ''"
            :value="source[field.id]"
            @input="updateField(field, $event)"
            :rows="field.id === 'bookSourceComment' ? 1 : 2"
          ></textarea>

          <textarea
            v-else-if="field.type === 'String' && field.namespace"
            class="web-textarea"
            :placeholder="field.hint || ''"
            :value="(source[field.namespace] || {})[field.id]"
            @input="updateNsField(field, $event)"
            :rows="2"
          ></textarea>

          <input
            v-else-if="field.type === 'Number'"
            class="web-input"
            type="number"
            :value="source[field.id]"
            @input="updateField(field, $event)"
          />

          <select
            v-else-if="field.type === 'Array'"
            class="web-select"
            :value="source[field.id]"
            @change="updateField(field, $event)"
          >
            <option
              v-for="(opt, oi) in field.array"
              :key="oi"
              :value="oi"
            >
              {{ opt }}
            </option>
          </select>

          <label v-else-if="field.type === 'Boolean'" class="web-switch">
            <input
              type="checkbox"
              :checked="source[field.id]"
              @change="updateBoolField(field, $event)"
            />
            <span class="web-switch__slider"></span>
          </label>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
defineProps<{ config: Record<string, any> }>()

const store = useSourceStore()
const source = computed(() => store.currentSource as Record<string, any>)
const activeTab = ref('base')

function updateField(field: any, e: Event) {
  const target = e.target as HTMLInputElement
  const val = field.type === 'Number' ? parseFloat(target.value) || 0 : target.value
  store.currentSource = { ...store.currentSource, [field.id]: val }
}

function updateNsField(field: any, e: Event) {
  const target = e.target as HTMLInputElement
  store.currentSource = {
    ...store.currentSource,
    [field.namespace]: {
      ...(source.value[field.namespace] || {}),
      [field.id]: target.value,
    },
  }
}

function updateBoolField(field: any, e: Event) {
  const target = e.target as HTMLInputElement
  store.currentSource = { ...store.currentSource, [field.id]: target.checked }
}
</script>

<style scoped>

.source-form {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.form-tabs {
  flex-shrink: 0;
}

.form-tabs .web-tabs {
  border-bottom: 2px solid var(--web-border-light);
}

.form-tabs .web-tab {
  background: none;
  font-size: 14px;
}

.form-body {
  flex: 1;
  overflow-y: auto;
  padding-top: 12px;
}
</style>
