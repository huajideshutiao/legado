/** https://github.com/gedoor/legado/tree/master/app/src/main/java/io/legado/app/api */
/** https://github.com/gedoor/legado/tree/master/app/src/main/java/io/legado/app/web */

import type { webReadConfig } from '@/web'
import ajax from './axios'
import type { BaseBook, Book, BookChapter, BookGroup, BookProgress, SeachBook } from '@/book'
import type { RawSource, Source } from '@/source'

export type LeagdoApiResponse<T> = {
  isSuccess: boolean
  errorMsg: string
  data: T
}

export let legado_http_entry_point = ''
export let legado_webSocket_entry_point = ''

let wsOnError: typeof WebSocket.prototype.onerror = () => {}
let wsOnMessage: typeof WebSocket.prototype.onmessage = () => {}
export const setWebsocketOnMessage = (callback: typeof wsOnMessage) =>
  (wsOnMessage = callback)
export const setWebsocketOnError = (callback: typeof wsOnError) => {
  wsOnError = callback
}

export const setApiEntryPoint = (
  http_entry_point: string,
  webSocket_entry_point: string,
) => {
  legado_http_entry_point = new URL(http_entry_point).toString()
  legado_webSocket_entry_point = new URL(webSocket_entry_point).toString()
  ajax.defaults.baseURL = legado_http_entry_point
}

// 书架API
const getReadConfig = async (http_url = legado_http_entry_point) => {
  const { data } = await ajax.get<LeagdoApiResponse<string>>('getReadConfig', {
    baseURL: http_url.toString(),
  })
  if (data.isSuccess) {
    try {
      return JSON.parse(data.data) as webReadConfig
    } catch {}
  }
}
const saveReadConfig = (config: webReadConfig) =>
  ajax.post<LeagdoApiResponse<unknown>>('saveReadConfig', config)

const saveBookProgress = (bookProgress: BookProgress) =>
  ajax.post<LeagdoApiResponse<unknown>>('saveBookProgress', bookProgress)

const saveBookProgressWithBeacon = (bookProgress: BookProgress) => {
  if (!bookProgress) return
  navigator.sendBeacon(
    new URL('saveBookProgress', legado_http_entry_point),
    JSON.stringify(bookProgress),
  )
}

const getGroups = () => ajax.get<LeagdoApiResponse<BookGroup[]>>('getGroups')

const getBookShelf = (groupId?: number | string) => {
  const url = groupId !== undefined ? `getBookshelf?groupId=${groupId}` : 'getBookshelf'
  return ajax.get<LeagdoApiResponse<Book[]>>(url)
}

export type CatalogBookMetadata = Pick<BaseBook, 'bookUrl' | 'name' | 'author'> & {
  origin?: string
  originName?: string
  tocUrl?: string
  type?: number
  coverUrl?: string
  intro?: string
  kind?: string
  wordCount?: string
  variable?: string
}

const catalogQuery = (book: CatalogBookMetadata) => {
  const params = new URLSearchParams({
    url: book.bookUrl,
    name: book.name,
    author: book.author,
  })
  const optional: Array<[string, unknown]> = [
    ['origin', book.origin],
    ['originName', book.originName],
    ['tocUrl', book.tocUrl],
    ['type', book.type],
    ['coverUrl', book.coverUrl],
    ['intro', book.intro],
    ['kind', book.kind],
    ['wordCount', book.wordCount],
    ['variable', book.variable],
  ]
  for (const [key, value] of optional) {
    if (value !== undefined && value !== null && value !== '') {
      params.set(key, String(value))
    }
  }
  return params.toString()
}

const getChapterList = (book: CatalogBookMetadata) =>
  ajax.get<LeagdoApiResponse<BookChapter[]>>('getChapterList?' + catalogQuery(book))

const refreshToc = (book: CatalogBookMetadata) =>
  ajax.get<LeagdoApiResponse<BookChapter[]>>('refreshToc?' + catalogQuery(book))

const getBookContent = (
  bookUrl: string,
  origin: string | undefined,
  chapterIndex: number,
  refresh = false,
) => {
  const params = new URLSearchParams({ url: bookUrl, index: String(chapterIndex) })
  if (origin) params.set('origin', origin)
  if (refresh) params.set('refresh', 'true')
  return ajax.get<LeagdoApiResponse<string>>('getBookContent?' + params.toString())
}

const search = (
  searchKey: string,
  onReceive: (data: SeachBook[]) => void,
  onFinish: () => void,
  scope?: string,
) => {
  const socket = new WebSocket(
    new URL('searchBook', legado_webSocket_entry_point),
  )
  let finished = false
  const finishOnce = () => {
    if (finished) return
    finished = true
    onFinish()
  }
  socket.onerror = wsOnError

  socket.onopen = () => {
    if (finished || socket.readyState !== WebSocket.OPEN) return
    const payload: { key: string; scope: string } = {
      key: searchKey,
      scope: scope && scope.trim() ? scope.trim() : 'all',
    }
    socket.send(JSON.stringify(payload))
  }
  socket.onmessage = event => {
    if (finished || event.currentTarget !== socket) return
    try {
      onReceive(JSON.parse(event.data))
      wsOnMessage?.call(socket, event)
    } catch {
      finishOnce()
    }
  }

  socket.onclose = event => {
    if (event.currentTarget === socket) finishOnce()
  }

  return socket
}

export interface WebExploreKind {
  title: string
  type?: string
  url?: string
  style?: {
    cols?: number
    rows?: number
    layout_flexBasisPercent?: number
  }
}

const saveBook = (book: BaseBook) => ajax.post<LeagdoApiResponse<unknown>>('saveBook', book)
const deleteBook = (book: BaseBook) => ajax.post<LeagdoApiResponse<unknown>>('deleteBook', book)

export interface WebBookSourcePart {
  bookSourceUrl: string
  bookSourceName: string
  bookSourceGroup?: string
  customOrder: number
  enabled: boolean
  enabledExplore: boolean
  hasLoginUrl: boolean
  lastUpdateTime: number
  respondTime: number
  weight: number
  hasExploreUrl: boolean
}

const getSources = () => ajax.get<LeagdoApiResponse<(RawSource | Source)[]>>('getBookSources')
const getBookSourcesPart = () =>
  ajax.get<LeagdoApiResponse<WebBookSourcePart[]>>('getBookSourcesPart')
const getSource = (url: string) =>
  ajax.get<LeagdoApiResponse<Source>>('getBookSource?url=' + encodeURIComponent(url))

/** 下载全端标准备份 zip (BackupShared 管线); 失败抛 Error(errorMsg) */
const getBackupZip = async (): Promise<Blob> => {
  const base = legado_http_entry_point || location.origin
  const resp = await fetch(new URL('getBackupZip', base).toString())
  if ((resp.headers.get('content-type') || '').includes('application/json')) {
    const env = (await resp.json()) as LeagdoApiResponse<unknown>
    throw new Error(env.errorMsg || '备份失败')
  }
  return await resp.blob()
}

/** 上传备份 zip 恢复 (multipart, 全端标准格式) */
const restoreBackup = async (file: File): Promise<LeagdoApiResponse<unknown>> => {
  const base = legado_http_entry_point || location.origin
  const fd = new FormData()
  fd.append('fileData', file, file.name)
  const resp = await fetch(new URL('restoreBackup', base).toString(), {
    method: 'POST',
    body: fd,
  })
  if (!resp.ok) {
    let msg = `HTTP ${resp.status}: ${resp.statusText || '请求失败'}`
    try {
      const errJson = await resp.json()
      if (errJson?.errorMsg) msg = errJson.errorMsg
    } catch {
      // ignore
    }
    throw new Error(msg)
  }
  return (await resp.json()) as LeagdoApiResponse<unknown>
}

/** 上传本地书 (multipart, 支持 txt/epub/zip/cbz) */
const addLocalBook = async (file: File): Promise<LeagdoApiResponse<unknown>> => {
  const base = legado_http_entry_point || location.origin
  const fd = new FormData()
  fd.append('fileData', file, file.name)
  const resp = await fetch(
    new URL(`addLocalBook?fileName=${encodeURIComponent(file.name)}`, base).toString(),
    {
      method: 'POST',
      body: fd,
    },
  )
  if (!resp.ok) {
    let msg = `HTTP ${resp.status}: ${resp.statusText || '上传失败'}`
    try {
      const errJson = await resp.json()
      if (errJson?.errorMsg) msg = errJson.errorMsg
    } catch {
      // ignore
    }
    throw new Error(msg)
  }
  return (await resp.json()) as LeagdoApiResponse<unknown>
}

const saveSource = (data: Source | RawSource) => ajax.post<LeagdoApiResponse<unknown>>('saveBookSource', data)

const saveSources = (data: (Source | RawSource)[]) => ajax.post<LeagdoApiResponse<unknown>>('saveBookSources', data)

const deleteSource = (data: (Source | RawSource)[]) => ajax.post<LeagdoApiResponse<unknown>>('deleteBookSources', data)

const debug = (
  sourceUrl: string,
  searchKey: string,
  onReceive: (data: string) => void,
  onFinish: () => void,
) => {
  const url = new URL(
    'bookSourceDebug',
    legado_webSocket_entry_point,
  )

  const socket = new WebSocket(url)
  socket.onerror = wsOnError
  socket.onopen = () => {
    socket.send(JSON.stringify({ tag: sourceUrl, key: searchKey }))
  }
  socket.onmessage = event => {
    onReceive(event.data)
    wsOnMessage?.call(socket, event)
  }

  socket.onclose = () => {
    onFinish()
  }
}

const toAbsoluteUrl = (path: string): string => {
  if (!path) return ''
  if (/^https?:\/\//i.test(path) || path.startsWith('data:') || path.startsWith('blob:')) {
    return path
  }
  const base = legado_http_entry_point || (typeof location !== 'undefined' ? location.origin : '')
  if (!base) return path
  const relative = path.startsWith('/') ? path.slice(1) : path
  return new URL(relative, base).toString()
}

const getProxyCoverUrl = (coverUrl: string) => {
  if (coverUrl.startsWith(legado_http_entry_point)) return coverUrl
  return new URL(
    'cover?path=' + encodeURIComponent(coverUrl),
    legado_http_entry_point,
  ).toString()
}

const getProxyImageUrl = (
  bookUrl: string,
  src: string,
  _width?: number | `${number}`,
) => {
  if (legado_http_entry_point && src.startsWith(legado_http_entry_point)) return src
  if (src.startsWith('/image?') || src.startsWith('image?')) {
    return toAbsoluteUrl(src)
  }
  return new URL(
    'image?path=' +
      encodeURIComponent(src) +
      '&url=' +
      encodeURIComponent(bookUrl),
    legado_http_entry_point,
  ).toString()
}

/**
 * 清洗媒体地址并仅返回浏览器可直接加载的 URL。
 * `origin` 为兼容旧调用签名保留；媒体代理已永久关闭。
 */
export const getMediaStreamUrl = (url: string, _origin?: string): string => {
  if (!url) return ''
  let clean = url.trim()
  const commaIdx = clean.indexOf(',{')
  if (commaIdx > 0) {
    clean = clean.substring(0, commaIdx).trim()
  }
  return /^(https?:\/\/|data:|blob:)/i.test(clean) ? clean : ''
}

const getExploreKinds = (url: string) =>
  ajax.get<LeagdoApiResponse<WebExploreKind[]>>(`getExploreKinds?url=${encodeURIComponent(url)}`)

const getExploreBooks = (url: string, exploreUrl: string, page: number = 1) =>
  ajax.get<LeagdoApiResponse<SeachBook[]>>(
    `getExploreBooks?url=${encodeURIComponent(url)}&exploreUrl=${encodeURIComponent(exploreUrl)}&page=${page}`,
  )

export default {
  get legado_http_entry_point() {
    return legado_http_entry_point
  },
  getReadConfig,
  saveReadConfig,
  saveBookProgress,
  saveBookProgressWithBeacon,
  getGroups,
  getBookShelf,
  getChapterList,
  refreshToc,
  getBookContent,
  search,
  saveBook,
  deleteBook,

  getSources,
  getBookSourcesPart,
  getSource,
  getExploreKinds,
  getExploreBooks,
  saveSources,
  getBackupZip,
  restoreBackup,
  addLocalBook,
  saveSource,
  deleteSource,
  debug,

  toAbsoluteUrl,
  getProxyCoverUrl,
  getProxyImageUrl,
  getMediaStreamUrl,
}

export {
  toAbsoluteUrl,
  getSources,
  getBookSourcesPart,
  refreshToc,
  getSource,
  getExploreKinds,
  getExploreBooks,
  getProxyCoverUrl,
  getProxyImageUrl,
}
