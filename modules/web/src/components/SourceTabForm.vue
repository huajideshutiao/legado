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
            :value="textValue(field)"
            @input="updateField(field, $event)"
            :rows="field.id === 'bookSourceComment' ? 1 : 2"
          ></textarea>

          <textarea
            v-else-if="field.type === 'String' && field.namespace"
            class="web-textarea"
            :placeholder="field.hint || ''"
            :value="textValue(field)"
            @input="updateNsField(field, $event)"
            :rows="2"
          ></textarea>

          <input
            v-else-if="field.type === 'Number'"
            class="web-input"
            type="number"
            :value="textValue(field)"
            @input="updateField(field, $event)"
          />

          <select
            v-else-if="field.type === 'Array'"
            class="web-select"
            :value="textValue(field)"
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
              :checked="boolValue(field)"
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
interface SourceField {
  id: string
  title?: string
  required?: boolean
  type?: string
  namespace?: string
  hint?: string
  array?: string[]
}

defineProps<{ config: Record<string, { name: string; children: SourceField[] }> }>()

const store = useSourceStore()
const source = computed(() => store.currentSource as Record<string, unknown>)
const activeTab = ref('base')

/** 取字段值: 普通字段直接取; namespace 字段取命名空间对象内的子字段 */
function fieldValue(field: SourceField): unknown {
  if (field.namespace) {
    const ns = source.value[field.namespace]
    return ns && typeof ns === 'object' ? (ns as Record<string, unknown>)[field.id] : undefined
  }
  return source.value[field.id]
}

/** 文本/数值类字段值 (textarea/input/select 的 :value 需要) */
function textValue(field: SourceField): string | number | null | undefined {
  const v = fieldValue(field)
  return typeof v === 'string' || typeof v === 'number' ? v : null
}

/** 布尔类字段值 (checkbox 的 :checked 需要) */
function boolValue(field: SourceField): boolean | undefined {
  const v = fieldValue(field)
  return typeof v === 'boolean' ? v : undefined
}

function updateField(field: SourceField, e: Event) {
  const target = e.target as HTMLInputElement
  const val = field.type === 'Number' ? parseFloat(target.value) || 0 : target.value
  store.currentSource = { ...store.currentSource, [field.id]: val }
}

function updateNsField(field: SourceField, e: Event) {
  const target = e.target as HTMLInputElement
  const nsObj = (source.value[field.namespace!] ?? {}) as Record<string, unknown>
  store.currentSource = {
    ...store.currentSource,
    [field.namespace!]: {
      ...nsObj,
      [field.id]: target.value,
    },
  }
}

function updateBoolField(field: SourceField, e: Event) {
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
