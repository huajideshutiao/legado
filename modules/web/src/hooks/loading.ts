import { watch, unref, onUnmounted } from 'vue'
import './loading.css'

const loadingSvg = `<svg viewBox="0 0 50 50" class="circular"><circle cx="50%" cy="50%" r="20" fill="none" stroke="currentColor" stroke-width="4" stroke-linecap="round" stroke-dasharray="31.416 31.416" stroke-dashoffset="0"><animateTransform attributeName="transform" type="rotate" from="0 25 25" to="360 25 25" dur="1s" repeatCount="indefinite"/></circle></svg>`

function createLoadingEl(text: string, spinner: string): HTMLElement {
  const div = document.createElement('div')
  div.className = 'web-loading-mask'
  div.innerHTML = `${spinner}<span style="color:var(--web-primary);font-size:14px">${text}</span>`
  return div
}

export const useLoading = (
  target: MaybeRef<string | HTMLElement | undefined>,
  text: string,
  spinner = loadingSvg,
) => {
  const isLoading = ref(false)
  let loadingEl: HTMLElement | null = null
  const closeLoading = () => (isLoading.value = false)
  const showLoading = () => (isLoading.value = true)

  watch(isLoading, loading => {
    if (loadingEl) {
      loadingEl.remove()
      loadingEl = null
    }
    if (!loading) return
    const t = unref(target)
    const container: HTMLElement | null =
      typeof t === 'string' ? document.querySelector(t) : t || null
    if (!container) return
    loadingEl = createLoadingEl(text, spinner)
    if (getComputedStyle(container).position === 'static') {
      container.style.position = 'relative'
    }
    container.appendChild(loadingEl)
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
