/** https://github.com/gedoor/legado/tree/master/app/src/main/java/io/legado/app/api */
/** https://github.com/gedoor/legado/tree/master/app/src/main/java/io/legado/app/web */

import type { webReadConfig } from '@/web'
import ajax from './axios'
import type {
  BaseBook,
  Book,
  BookChapter,
  BookProgress,
  BookGroup,
  SeachBook,
} from '@/book'
import type { Source } from '@/source'

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
  const { data } = await ajax.get('getReadConfig', {
    baseURL: http_url.toString(),
  })
  if (data.isSuccess) {
    try {
      return JSON.parse(data.data) as webReadConfig
    } catch {}
  }
}
const saveReadConfig = (config: webReadConfig) =>
  ajax.post('saveReadConfig', config)

const saveBookProgress = (bookProgress: BookProgress) =>
  ajax.post('saveBookProgress', bookProgress)

const saveBookProgressWithBeacon = (bookProgress: BookProgress) => {
  if (!bookProgress) return
  navigator.sendBeacon(
    new URL('saveBookProgress', legado_http_entry_point),
    JSON.stringify(bookProgress),
  )
}

const getGroups = () => ajax.get('getGroups')

const getBookShelf = (groupId?: number | string) => {
  const url = groupId !== undefined ? `getBookshelf?groupId=${groupId}` : 'getBookshelf'
  return ajax.get(url)
}

const getChapterList = (bookUrl: string) =>
  ajax.get('getChapterList?url=' + encodeURIComponent(bookUrl))

const getBookContent = (bookUrl: string, chapterIndex: number) =>
  ajax.get('getBookContent?url=' + encodeURIComponent(bookUrl) + '&index=' + chapterIndex)

const search = (
  searchKey: string,
  onReceive: (data: SeachBook[]) => void,
  onFinish: () => void,
) => {
  const socket = new WebSocket(
    new URL('searchBook', legado_webSocket_entry_point),
  )
  socket.onerror = wsOnError

  socket.onopen = () => {
    socket.send(`{"key":"${searchKey}"}`)
  }
  socket.onmessage = event => {
    try {
      onReceive(JSON.parse(event.data))
      wsOnMessage?.call(socket, event)
    } catch {
      onFinish()
    }
  }

  socket.onclose = () => {
    onFinish()
  }
}

const saveBook = (book: BaseBook) => ajax.post('saveBook', book)
const deleteBook = (book: BaseBook) => ajax.post('deleteBook', book)

const getSources = () => ajax.get('getBookSources')

const saveSource = (data: Source) => ajax.post('saveBookSource', data)

const saveSources = (data: Source[]) => ajax.post('saveBookSources', data)

const deleteSource = (data: Source[]) => ajax.post('deleteBookSources', data)

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
  width: number | `${number}`,
) => {
  if (src.startsWith(legado_http_entry_point)) return src
  return new URL(
    'image?path=' +
      encodeURIComponent(src) +
      '&url=' +
      encodeURIComponent(bookUrl) +
      '&width=' +
      width,
    legado_http_entry_point,
  ).toString()
}

export default {
  getReadConfig,
  saveReadConfig,
  saveBookProgress,
  saveBookProgressWithBeacon,
  getGroups,
  getBookShelf,
  getChapterList,
  getBookContent,
  search,
  saveBook,
  deleteBook,

  getSources,
  saveSources,
  saveSource,
  deleteSource,
  debug,

  getProxyCoverUrl,
  getProxyImageUrl,
}
