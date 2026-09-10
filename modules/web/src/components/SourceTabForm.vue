<template>
  <div class="source-form">
    <div class="form-tabs" ref="tabsRef" @wheel.passive="handleTabsWheel">
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
            v-if="field.type === 'String'"
            class="web-textarea auto-grow"
            :placeholder="field.hint || ''"
            :value="textValue(field)"
            @input="onStringInput(field, $event)"
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
const tabsRef = ref<HTMLElement>()

function handleTabsWheel(e: WheelEvent) {
  const el = tabsRef.value?.querySelector('.web-tabs') as HTMLElement | null
  if (el && e.deltaY) {
    el.scrollLeft += e.deltaY
  }
}

function getNsObject(nsValue: unknown): Record<string, unknown> {
  if (nsValue && typeof nsValue === 'object') {
    return nsValue as Record<string, unknown>
  }
  if (typeof nsValue === 'string') {
    const trimmed = nsValue.trim()
    if (trimmed.startsWith('{') || trimmed.startsWith('[')) {
      try {
        const parsed = JSON.parse(trimmed)
        if (parsed && typeof parsed === 'object') {
          return parsed as Record<string, unknown>
        }
      } catch {
        return {}
      }
    }
  }
  return {}
}

/** 取字段值: 普通字段直接取; namespace 字段取命名空间对象内的子字段 */
function fieldValue(field: SourceField): unknown {
  if (field.namespace) {
    const nsObj = getNsObject(source.value[field.namespace])
    return nsObj[field.id]
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
  const nsObj = getNsObject(source.value[field.namespace!])
  store.currentSource = {
    ...store.currentSource,
    [field.namespace!]: {
      ...nsObj,
      [field.id]: target.value,
    },
  }
}

function onStringInput(field: SourceField, e: Event) {
  if (field.namespace) {
    updateNsField(field, e)
  } else {
    updateField(field, e)
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
  overflow: hidden;
}

.form-tabs .web-tabs {
  border-bottom: 2px solid var(--web-border-light);
  overflow-x: auto;
  flex-wrap: nowrap;
  scrollbar-width: none;
  -ms-overflow-style: none;
}

.form-tabs .web-tabs::-webkit-scrollbar {
  display: none;
}

.form-tabs .web-tab {
  background: none;
  font-size: 14px;
  flex-shrink: 0;
  white-space: nowrap;
}

.form-body {
  flex: 1;
  overflow-y: auto;
  padding-top: 12px;
}

.web-textarea.auto-grow {
  min-height: 40px;
  height: auto;
  field-sizing: content;
  overflow-y: hidden;
  resize: none;
  line-height: 1.5;
  font-family: 'Consolas', 'Courier New', monospace;
  box-sizing: border-box;
  transition: border-color 0.15s ease;
}
</style>
