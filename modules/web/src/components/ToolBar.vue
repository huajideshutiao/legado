<template>
  <div class="menu flex-column-center">
    <button
      v-for="button in buttons"
      class="web-btn"
      :key="button.name"
      @click="button.action"
    >
      {{ button.name }}
    </button>
    <button class="web-btn" @click="() => (hotkeysDialogVisible = true)">快捷键</button>
  </div>
  <div v-if="hotkeysDialogVisible" class="web-dialog-overlay" @click.self="stopRecordKeyDown">
    <div class="web-dialog">
      <div class="web-dialog__header">
        <span>
          快捷键设置
          <span v-if="recordKeyDowning" class="web-text web-text--secondary"> / 录入中 </span>
        </span>
        <div style="display:flex;gap:8px">
          <button class="web-btn web-btn--primary" :disabled="recordKeyDowning" @click="saveHotKeys">保存</button>
          <button class="web-dialog__close" @click="stopRecordKeyDown">&times;</button>
        </div>
      </div>
      <div class="hotkeys-settings flex-column-center">
        <div v-for="(button, buttonIndex) in buttons" :key="button.name" class="hotkeys-item flex-space-between">
          <span class="title">{{ button.name }}</span>
          <div class="hotkeys-item__content">
            <template v-for="(key, hotKeysIndex) in button.hotKeys" :key="key">
              <kbd>{{ key }}</kbd>
              <span v-if="hotKeysIndex + 1 < button.hotKeys.length">+</span>
            </template>
            <span v-if="button.hotKeys.length == 0">未设置</span>
          </div>
          <button class="web-btn web-btn--text" :disabled="recordKeyDowning" @click="recordKeyDown(buttonIndex)">编辑</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import API from '@api'
import hotkeys from 'hotkeys-js'
import { getSourceName, isInvaildSource, normalizeSource } from '../utils/souce'
import { toast } from '@/utils/toast'

const store = useSourceStore()
const pull = () => {
  toast.info({ message: '加载中……', showClose: true, duration: 0 })
  API.getSources()
    .then(({ data }) => {
      if (data.isSuccess) {
        store.changeTabName('editList')
        store.saveSources(data.data)
        toast.success(`成功拉取${data.data.length}条源`)
      } else {
        toast.error(data.errorMsg ?? '后端错误')
      }
    })
    .finally(() => toast.close())
}

const push = () => {
  const sources = store.sources
  store.changeTabName('editList')
  if (sources.length === 0) {
    return toast.info('空空如也')
  }
  toast.info('正在推送中')
  API.saveSources(sources).then(({ data }) => {
    if (data.isSuccess) {
      const okData = data.data
      if (Array.isArray(okData)) {
        let failMsg = ``
        if (sources.length > okData.length) {
          failMsg = '\n推送失败的源将用红色字体标注!'
          store.setPushReturnSources(okData)
        }
        toast.success(`批量推送源到「阅读3.0APP」\n共计: ${sources.length} 条\n成功: ${okData.length} 条\n失败: ${sources.length - okData.length} 条${failMsg}`)
      }
    } else {
      toast.error(`批量推送源失败!\nErrorMsg: ${data.errorMsg}`)
    }
  })
}

const conver2Tab = () => {
  store.changeTabName('editTab')
  store.changeEditTabSource(store.currentSource)
}
const conver2Source = () => {
  store.changeCurrentSource(store.editTabSource)
}

const undo = () => {
  store.editHistoryUndo()
}

const clearEdit = () => {
  store.clearEdit()
  toast.success('已清除')
}

const redo = () => {
  store.clearEdit()
  store.clearAllHistory()
  toast.success('已清除所有历史记录')
}

const saveSource = () => {
  const source = store.currentSource
  if (isInvaildSource(source)) {
    normalizeSource(source)
    API.saveSource(source).then(({ data }) => {
      const sourceName = getSourceName(source)
      if (data.isSuccess) {
        toast.success(`源《${sourceName}》已成功保存到「阅读3.0APP」`)
        store.saveCurrentSource()
      } else {
        toast.error(`源《${sourceName}》保存失败!\nErrorMsg: ${data.errorMsg}`)
      }
    })
  } else {
    toast.error(`请检查<必填>项是否全部填写`)
  }
}

const debug = () => {
  store.startDebug()
}

const buttons = ref<{ name: string; hotKeys: string[]; action: () => void }[]>(
  Array.of(
    { name: '⇈推送源', hotKeys: [], action: push },
    { name: '⇊拉取源', hotKeys: [], action: pull },
    { name: '⋙生成源', hotKeys: [], action: conver2Tab },
    { name: '⋘编辑源', hotKeys: [], action: conver2Source },
    { name: '✗清空表单', hotKeys: [], action: clearEdit },
    { name: '↶撤销操作', hotKeys: [], action: undo },
    { name: '↷重做操作', hotKeys: [], action: redo },
    { name: '⇏调试源', hotKeys: [], action: debug },
    { name: '✓保存源', hotKeys: [], action: saveSource },
  ),
)
const hotkeysDialogVisible = ref(true)

const recordKeyDowning = ref(false)

const recordKeyDownIndex = ref(-1)

const stopRecordKeyDown = () => {
  if (!recordKeyDowning.value) {
    hotkeysDialogVisible.value = false
  }
  recordKeyDowning.value = false
}

watch(
  hotkeysDialogVisible,
  visibale => {
    if (!visibale) {
      hotkeys.unbind('*')
      readHotkeysConfig()
      bindHotKeys()
      return
    }
    readHotkeysConfig()
    hotkeys.unbind()
    hotkeys('*', event => {
      event.preventDefault()
      const pressedKeys = hotkeys.getPressedKeyString()
      if (pressedKeys.length == 1 && pressedKeys[0] == 'esc') {
        return
      }
      if (recordKeyDowning.value && recordKeyDownIndex.value > -1)
        buttons.value[recordKeyDownIndex.value].hotKeys = pressedKeys
    })
  },
  { immediate: true },
)

const recordKeyDown = (index: number) => {
  recordKeyDowning.value = true
  toast.info('按ESC键或者点击空白处结束录入')
  buttons.value[index].hotKeys = []
  recordKeyDownIndex.value = index
}

const saveHotKeys = () => {
  const hotKeysConfig: string[][] = []
  buttons.value.forEach(({ hotKeys }) => {
    hotKeysConfig.push(hotKeys)
  })
  saveHotkeysConfig(hotKeysConfig)
  hotkeysDialogVisible.value = false
}

const bindHotKeys = () => {
  hotkeys.filter = () => true
  buttons.value.forEach(({ hotKeys, action }) => {
    if (hotKeys.length == 0) return
    hotkeys(hotKeys.join('+'), event => {
      event.preventDefault()
      action.call(null)
    })
  })
}
const saveHotkeysConfig = (config: string[][]) => {
  localStorage.setItem('legado_web_hotkeys', JSON.stringify(config))
}

function readHotkeysConfig() {
  try {
    const localStorageConfig = localStorage.getItem('legado_web_hotkeys')
    if (localStorageConfig === null) return false
    const config = JSON.parse(localStorageConfig)
    if (!Array.isArray(config) || config.length == 0) return false
    buttons.value.forEach((button, index) => (button.hotKeys = config[index]))
    return true
  } catch {
    toast.error('快捷键配置错误')
    localStorage.removeItem('legado_web_hotkeys')
  }
  return false
}

onMounted(() => {
  if (readHotkeysConfig()) {
    hotkeysDialogVisible.value = false
  }
})
</script>

<style lang="scss" scoped>
.flex-space-between {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
}
.flex-column-center {
  display: flex;
  flex-direction: column;
  justify-content: center;
}

.menu > .web-btn {
  margin: 4px;
  padding: 1em;
  width: 6em;
}

.hotkeys-item {
  .title {
    width: 5em;
    display: flex;
    justify-content: flex-end;
    margin-right: 1em;
  }
  .hotkeys-item__content {
    display: flex;
    flex-wrap: wrap;
    flex: 1;
    div {
      margin-bottom: 1em;
    }
    span {
      margin: 0.5em;
    }
  }
}
</style>
