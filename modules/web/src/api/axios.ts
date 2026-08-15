/** @type {string} localStorage保存自定义阅读http服务接口的键值 */
export const baseURL_localStorage_key = 'remoteUrl'

const baseURL =
  (typeof import.meta !== 'undefined' &&
    (import.meta as { env?: Record<string, string | undefined> }).env?.VITE_API) ||
  (typeof localStorage !== 'undefined' ? localStorage.getItem(baseURL_localStorage_key) : null) ||
  (typeof location !== 'undefined' ? location.origin : '')

interface FetchConfig {
  baseURL?: string
  timeout?: number
}

/** 请求参数: RequestInit + 额外 baseURL (仅随 options 传递, URL 拼接走 defaults.baseURL) */
type RequestOptions = RequestInit & { baseURL?: string }

interface RequestConfig extends FetchConfig {
  url: string
  options?: RequestOptions
}

export interface ApiResponse<T = unknown> {
  data: T
  status: number
  headers: Headers
  config: RequestConfig
}

interface RequestInterceptor {
  onFulfilled?: (
    config: RequestConfig,
  ) => RequestConfig | undefined | Promise<RequestConfig | undefined>
  onRejected?: (error: unknown) => unknown
}

interface ResponseInterceptor {
  onFulfilled?: (
    response: ApiResponse,
  ) => ApiResponse | undefined | Promise<ApiResponse | undefined>
  onRejected?: (error: unknown) => unknown
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

  async _request<T = unknown>(url: string, options: RequestOptions = {}): Promise<ApiResponse<T>> {
    const fullUrl = url.startsWith('http') ? url : this.defaults.baseURL + url
    let req: RequestConfig = { url: fullUrl, options }

    for (const interceptor of this._reqInterceptors) {
      if (interceptor.onFulfilled) {
        req = (await interceptor.onFulfilled(req)) || req
      }
    }

    const response = await fetch(req.url, {
      ...req.options,
      headers: {
        'Content-Type': 'application/json',
        ...(req.options?.headers || {}),
      },
    })
    const data = (await response.json()) as T
    let result: ApiResponse<T> = {
      data,
      status: response.status,
      headers: response.headers,
      config: req,
    }

    for (const interceptor of this._resInterceptors) {
      if (interceptor.onFulfilled) {
        // 响应拦截器只校验/透传 LeagdoApiResponse 信封, 不改变 data 类型
        result = ((await interceptor.onFulfilled(result)) ?? result) as ApiResponse<T>
      }
    }

    return result
  }

  get<T = unknown>(url: string, config?: FetchConfig) {
    return this._request<T>(url, {
      method: 'GET',
      ...(config?.baseURL ? { baseURL: config.baseURL } : {}),
    })
  }

  post<T = unknown>(url: string, data?: unknown, config?: FetchConfig) {
    return this._request<T>(url, {
      method: 'POST',
      body: JSON.stringify(data),
      ...(config?.baseURL ? { baseURL: config.baseURL } : {}),
    })
  }

  async _simpleGet(url: string): Promise<{ data: unknown }> {
    const fullUrl = url.startsWith('http') ? url : this.defaults.baseURL + url
    const response = await fetch(fullUrl, {
      headers: { 'Content-Type': 'application/json' },
    })
    return { data: await response.json() }
  }

  async _simplePost(url: string, data?: unknown): Promise<{ data: unknown }> {
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
