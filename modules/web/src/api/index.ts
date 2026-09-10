import type { LeagdoApiResponse } from './api'
import API, {
  setWebsocketOnError,
  setApiEntryPoint,
  legado_http_entry_point,
  setWebsocketOnMessage,
} from './api'
import ajax, { type ApiResponse } from './axios'
import { validatorHttpUrl } from '@/utils/utils'
import { toast } from '@/utils/toast'
import { useConnectionStore } from '@/store'

// 拦截器内惰性取 connection store: 模块层不能引 App.vue 建 app —— App.vue 现经
// TabBar 引 @/store, store 又引 @api, 模块层 createApp(App) 会成
// App→TabBar→store→@api→App 循环依赖, 单文件产物里 App 半初始化直接崩。
// 首次请求发生时 main.ts 已 app.use(pinia), activePinia 就绪。
let connectionStore: ReturnType<typeof useConnectionStore> | null = null
const conn = () => (connectionStore ??= useConnectionStore())

const LeagdoApiResponseKeys: string[] = Array.of('isSuccess', 'errorMsg')

/** Interceptor: check if resp is LeagdoApiResponse*/
const responseCheckInterceptor = (resp: ApiResponse) => {
  let isLeagdoApiResponse = true
  try {
    const data = resp.data as Record<string, unknown>

    for (const key of LeagdoApiResponseKeys) {
      if (!(key in data)) {
        isLeagdoApiResponse = false
        LeagdoApiResponseKeys.length = 0
      }
    }
    if ((data as LeagdoApiResponse<unknown>).isSuccess === true) {
      if (!('data' in data)) {
        isLeagdoApiResponse = false
      }
    }
  } catch {
    isLeagdoApiResponse = false
  }
  if (isLeagdoApiResponse === false) {
    toast.warning({ message: '后端返回内容格式错误', grouping: true })
    throw new Error()
  }
  conn().setConnectType('primary')
  conn().setConnectStatus('已连接 ' + legado_http_entry_point)
  return resp
}

const fetchErrorInterceptor = (err: unknown) => {
  toast.error({
    message: '后端连接失败，请检查阅读WEB服务或者设置其它可用链接',
    grouping: true,
  })
  conn().setConnectType('danger')
  conn().setConnectStatus('连接异常')
  throw err
}
// http全局
ajax.interceptors.response.use(responseCheckInterceptor, fetchErrorInterceptor)
// websocket
setWebsocketOnError(fetchErrorInterceptor)
setWebsocketOnMessage(() => {
  conn().setConnectType('primary')
  conn().setConnectStatus('已连接 ' + legado_http_entry_point)
})
/**
 * 按照阅读的默认规则 解析阅读HTTP WebSocket API入口地址
 */
export const parseLeagdoHttpUrlWithDefault = (
  http_url: string | URL,
): [string, string] => {
  let url = new URL(location.origin)
  if (validatorHttpUrl(http_url)) {
    url = new URL(http_url)
  }
  const { protocol, port } = url
  let legado_webSocket_port
  // 开发期 Vite (默认端口 8080): 本地无头后端 WebSocket 端口固定在 1123
  if (port === '8080') {
    legado_webSocket_port = '1123'
  } else if (port !== '') {
    legado_webSocket_port = String(Number(port) + 1)
  } else {
    legado_webSocket_port = protocol.startsWith('https:') ? '444' : '81'
  }
  const legado_webSocket_protocol = protocol.startsWith('https:')
    ? 'wss://'
    : 'ws://'

  const http_entry_point = url.toString()

  url.protocol = legado_webSocket_protocol
  url.port = legado_webSocket_port
  const webSocket_entry_point = url.toString()

  console.info('legado_api_config:')
  console.table({
    'http API入口': http_entry_point,
    'webSocket API入口': webSocket_entry_point,
  })
  return [http_entry_point, webSocket_entry_point]
}

setApiEntryPoint(
  ...parseLeagdoHttpUrlWithDefault(ajax.defaults.baseURL as string),
)

export default API
export * from './api'
