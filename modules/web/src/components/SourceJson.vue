<template>
  <textarea
    id="source-json"
    class="web-textarea"
    v-model="sourceString"
    placeholder="这里输出序列化的JSON数据,可直接导入'阅读'APP"
    :rows="30"
    @change="update(sourceString)"
  ></textarea>
</template>
<script setup lang="ts">
import { useSourceStore } from '@/store'
import { toast } from '@/utils/toast'

const store = useSourceStore()
const sourceString = ref('')
const update = async (string: string) => {
  try {
    store.changeEditTabSource(JSON.parse(string))
  } catch {
    toast.error('粘贴的源格式错误')
  }
}

watchEffect(async () => {
  const source = store.editTabSource
  if (Object.keys(source).length > 0) {
    sourceString.value = JSON.stringify(source, null, 4)
  } else {
    sourceString.value = ''
  }
})
</script>
<style lang="scss" scoped>
#source-json {
  width: 100%;
  height: calc(100vh - 50px);
}
</style>
