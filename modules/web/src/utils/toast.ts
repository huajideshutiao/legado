let _toastTimer = 0
let _toastEl: HTMLElement | null = null

interface ToastOptions {
  message: string
  type?: 'info' | 'success' | 'error' | 'warning'
  duration?: number
  grouping?: boolean
  showClose?: boolean
  onClose?: () => void
}

function createToastEl(type: string): HTMLElement {
  const el = document.createElement('div')
  el.className = `web-toast web-toast--${type}`
  el.setAttribute('role', 'alert')
  el.addEventListener('click', () => dismissToast())
  return el
}

function dismissToast() {
  if (_toastEl) {
    _toastEl.classList.add('web-toast--leaving')
    setTimeout(() => {
      _toastEl?.remove()
      _toastEl = null
    }, 200)
  }
  clearTimeout(_toastTimer)
}

function showToast(opts: ToastOptions | string, type?: string) {
  const options: ToastOptions =
    typeof opts === 'string' ? { message: opts, type: (type as ToastOptions['type']) || 'info' } : opts

  dismissToast()

  _toastEl = createToastEl(options.type || 'info')
  _toastEl.textContent = options.message

  if (options.showClose) {
    const closeBtn = document.createElement('span')
    closeBtn.className = 'web-toast__close'
    closeBtn.textContent = '×'
    closeBtn.addEventListener('click', e => {
      e.stopPropagation()
      options.onClose?.()
      dismissToast()
    })
    _toastEl.appendChild(closeBtn)
  }

  document.body.appendChild(_toastEl)

  if (options.duration !== 0) {
    _toastTimer = window.setTimeout(() => {
      options.onClose?.()
      dismissToast()
    }, options.duration || 3000)
  }
}

export const toast = {
  info(msg: string | ToastOptions) {
    showToast(typeof msg === 'string' ? { message: msg, type: 'info' } : { ...msg, type: 'info' })
  },
  success(msg: string | ToastOptions) {
    showToast(typeof msg === 'string' ? { message: msg, type: 'success' } : { ...msg, type: 'success' })
  },
  error(msg: string | ToastOptions) {
    showToast(typeof msg === 'string' ? { message: msg, type: 'error' } : { ...msg, type: 'error' })
  },
  warning(msg: string | ToastOptions) {
    showToast(typeof msg === 'string' ? { message: msg, type: 'warning' } : { ...msg, type: 'warning' })
  },
  close() {
    dismissToast()
  },
}

interface ConfirmOptions {
  title?: string
  message: string
  type?: 'info' | 'success' | 'error' | 'warning'
  confirmButtonText?: string
  cancelButtonText?: string
  closeOnHashChange?: boolean
}

function showConfirm(options: ConfirmOptions): Promise<'confirm' | 'cancel'> {
  return new Promise((resolve, reject) => {
    const overlay = document.createElement('div')
    overlay.className = 'web-confirm-overlay'

    const box = document.createElement('div')
    box.className = 'web-confirm-box'
    box.innerHTML = `
      <div class="web-confirm-header">
        <span class="web-confirm-icon web-confirm-icon--${options.type || 'info'}">${options.type === 'error' ? '✕' : options.type === 'warning' ? '!' : 'i'}</span>
        <span>${options.title || '提示'}</span>
      </div>
      <div class="web-confirm-body">${options.message}</div>
      <div class="web-confirm-footer">
        <button class="web-btn web-btn--cancel">${options.cancelButtonText || '取消'}</button>
        <button class="web-btn web-btn--primary">${options.confirmButtonText || '确定'}</button>
      </div>
    `

    overlay.appendChild(box)
    document.body.appendChild(overlay)

    const hashHandler = () => {
      if (options.closeOnHashChange !== false) {
        cleanup()
        reject('cancel')
      }
    }

    if (options.closeOnHashChange !== false) {
      window.addEventListener('hashchange', hashHandler)
    }

    function cleanup() {
      overlay.remove()
      window.removeEventListener('hashchange', hashHandler)
    }

    overlay.addEventListener('click', e => {
      if (e.target === overlay) {
        cleanup()
        reject('cancel')
      }
    })

    box.querySelector('.web-btn--cancel')!.addEventListener('click', () => {
      cleanup()
      reject('cancel')
    })

    box.querySelector('.web-btn--primary')!.addEventListener('click', () => {
      cleanup()
      resolve('confirm')
    })
  })
}

export const msgbox = {
  confirm(message: string, title?: string, options?: Partial<ConfirmOptions>) {
    return showConfirm({ message, title, ...options })
  },
  prompt(
    message: string,
    title: string,
    options?: {
      inputPlaceholder?: string
      inputValidator?: (value: string) => boolean
      inputErrorMessage?: string
      confirmButtonText?: string
      cancelButtonText?: string
    },
  ): Promise<{ action: string; value: string }> {
    return new Promise((resolve, reject) => {
      const overlay = document.createElement('div')
      overlay.className = 'web-confirm-overlay'

      const box = document.createElement('div')
      box.className = 'web-confirm-box'
      box.innerHTML = `
        <div class="web-confirm-header"><span>${title}</span></div>
        <div class="web-confirm-body">${message}</div>
        <div class="web-form-group" style="padding:0 16px">
          <input class="web-input web-prompt-input" placeholder="${options?.inputPlaceholder || ''}" />
          <div class="web-prompt-error" style="display:none;color:var(--danger,#f56c6c);font-size:12px;margin-top:4px">${options?.inputErrorMessage || ''}</div>
        </div>
        <div class="web-confirm-footer">
          <button class="web-btn web-btn--cancel">${options?.cancelButtonText || '取消'}</button>
          <button class="web-btn web-btn--primary">${options?.confirmButtonText || '确定'}</button>
        </div>
      `

      overlay.appendChild(box)
      document.body.appendChild(overlay)

      const input = box.querySelector('.web-prompt-input') as HTMLInputElement
      const errorEl = box.querySelector('.web-prompt-error') as HTMLElement

      function cleanup() {
        overlay.remove()
      }

      overlay.addEventListener('click', e => {
        if (e.target === overlay) {
          cleanup()
          resolve({ action: 'cancel', value: input.value })
        }
      })

      box.querySelector('.web-btn--cancel')!.addEventListener('click', () => {
        cleanup()
        resolve({ action: 'cancel', value: input.value })
      })

      box.querySelector('.web-btn--primary')!.addEventListener('click', () => {
        if (options?.inputValidator && !options.inputValidator(input.value)) {
          errorEl.style.display = 'block'
          return
        }
        cleanup()
        resolve({ action: 'confirm', value: input.value })
      })

      setTimeout(() => input.focus(), 100)
    })
  },
}
