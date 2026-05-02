/** @type {string} localStorage保存自定义阅读http服务接口的键值 */
export const baseURL_localStorage_key = 'remoteUrl'
const SECOND = 1000

const baseURL =
  (typeof import.meta !== 'undefined' && (import.meta as any).env?.VITE_API) ||
  (typeof localStorage !== 'undefined' ? localStorage.getItem(baseURL_localStorage_key) : null) ||
  (typeof location !== 'undefined' ? location.origin : '')

interface FetchConfig {
  baseURL?: string
  timeout?: number
}

interface RequestInterceptor {
  onFulfilled?: (config: FetchConfig & { url: string; options?: RequestInit }) => any
  onRejected?: (error: any) => any
}

interface ResponseInterceptor {
  onFulfilled?: (response: any) => any
  onRejected?: (error: any) => any
}

class FetchWrapper {
  defaults: { baseURL: string }
  private _reqInterceptors: RequestInterceptor[] = []
  private _resInterceptors: ResponseInterceptor[] = []

  constructor(config: FetchConfig = {}) {
    this.defaults = {
      baseURL: config.baseURL || baseURL || '',
    }
  }

  get interceptors() {
    return {
      request: {
        use: (onFulfilled: RequestInterceptor['onFulfilled'], onRejected?: RequestInterceptor['onRejected']) => {
          this._reqInterceptors.push({ onFulfilled, onRejected })
        },
      },
      response: {
        use: (onFulfilled: ResponseInterceptor['onFulfilled'], onRejected?: ResponseInterceptor['onRejected']) => {
          this._resInterceptors.push({ onFulfilled, onRejected })
        },
      },
    }
  }

  async _request(url: string, options: RequestInit = {}): Promise<any> {
    const fullUrl = url.startsWith('http') ? url : this.defaults.baseURL + url
    let req = { url: fullUrl, options }

    for (const interceptor of this._reqInterceptors) {
      if (interceptor.onFulfilled) {
        req = await interceptor.onFulfilled(req) || req
      }
    }

    const response = await fetch(req.url, {
      ...req.options,
      headers: {
        'Content-Type': 'application/json',
        ...((req.options as any)?.headers || {}),
      },
    })
    const data = await response.json()
    let result = { data, status: response.status, headers: response.headers, config: req }

    for (const interceptor of this._resInterceptors) {
      if (interceptor.onFulfilled) {
        result = await interceptor.onFulfilled(result) || result
      }
    }

    return result
  }

  get(url: string, config?: { baseURL?: string; timeout?: number }) {
    return this._request(url, {
      method: 'GET',
      ...(config?.baseURL ? { baseURL: config.baseURL } as any : {}),
    })
  }

  post(url: string, data?: any, config?: { baseURL?: string }) {
    return this._request(url, {
      method: 'POST',
      body: JSON.stringify(data),
      ...(config?.baseURL ? { baseURL: config.baseURL } as any : {}),
    })
  }

  async _simpleGet(url: string): Promise<any> {
    const fullUrl = url.startsWith('http') ? url : this.defaults.baseURL + url
    const response = await fetch(fullUrl, {
      headers: { 'Content-Type': 'application/json' },
    })
    return { data: await response.json() }
  }

  async _simplePost(url: string, data?: any): Promise<any> {
    const fullUrl = url.startsWith('http') ? url : this.defaults.baseURL + url
    const response = await fetch(fullUrl, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(data),
    })
    return { data: await response.json() }
  }
}

const ajax = new FetchWrapper({ baseURL })

export default ajax
