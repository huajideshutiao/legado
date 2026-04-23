import { watch, unref, onUnmounted } from 'vue'
import { ElLoading } from 'element-plus'
import 'element-plus/theme-chalk/el-loading.css'
import './loading.css'

const loadingSvg = `<svg viewBox="0 0 50 50" class="circular"><circle cx="50%" cy="50%" r="20" fill="none" stroke="currentColor" stroke-width="4" stroke-linecap="round" stroke-dasharray="31.416 31.416" stroke-dashoffset="0"><animateTransform attributeName="transform" type="rotate" from="0 25 25" to="360 25 25" dur="1s" repeatCount="indefinite"/></circle></svg>`

export const useLoading = (
  target: MaybeRef<string | HTMLElement | undefined>,
  text: string,
  spinner = loadingSvg,
) => {
  // loading spinner
  const isLoading = ref(false)
  let loadingInstance: ReturnType<typeof ElLoading.service> | null = null
  const closeLoading = () => (isLoading.value = false)
  const showLoading = () => (isLoading.value = true)
  watch(isLoading, loading => {
    if (!loading) return loadingInstance?.close()
    loadingInstance = ElLoading.service({
      target: unref(target),
      spinner: spinner,
      text: text,
      lock: true,
      background: 'rgba(0, 0, 0, 0)',
    })
  })

  const loadingWrapper = (promise: Promise<unknown>) => {
    if (!(promise instanceof Promise))
      throw TypeError('loadingWrapper argument must be Promise')
    showLoading()
    return promise.finally(closeLoading)
  }

  onUnmounted(() => {
    closeLoading()
  })

  return { isLoading, showLoading, closeLoading, loadingWrapper }
}
