import { defineStore } from 'pinia'
import API from '@api'
import type {
  BaseBook,
  Book,
  BookChapter,
  BookProgress,
  BookGroup,
  SeachBook,
} from '@/book'
import type { webReadConfig } from '@/web'
import { toast } from '@/utils/toast'
import { toRaw } from 'vue'

const default_config: webReadConfig = {
  theme: 0,
  font: 0,
  fontSize: 18,
  readWidth: 800,
  infiniteLoading: false,
  customFontName: '',
  jumpDuration: 1000,
  spacing: {
    paragraph: 1,
    line: 0.8,
    letter: 0,
  },
  readerMode: 'auto',
}
let webReadConfigLoadedDate: Date | undefined

export const useBookStore = defineStore('book', {
  state: () => {
    return {
      searchBooks: [] as SeachBook[],
      shelf: [] as Book[],
      groups: [] as BookGroup[],
      currentGroupId: undefined as number | string | undefined,
      shelfGroupCache: {} as Record<string, Book[]>,
      catalog: [] as BookChapter[],
      catalogIdentity: '',
      catalogGeneration: 0,
      readingBook: { chapterPos: 0, chapterIndex: 0 } as BaseBook & {
        chapterPos: number
        chapterIndex: number
        isSeachBook?: boolean
        type?: number
        coverUrl?: string
        origin?: string
      },
      contentLoading: true,
      showContent: false,
      config: default_config,
      miniInterface: false,
      readSettingsVisible: false,
      detailBook: null as Book | SeachBook | null,
    }
  },
  getters: {
    bookProgress: (state): BookProgress | undefined => {
      if (state.catalog.length == 0) return
      const { chapterIndex, chapterPos, name, author } = state.readingBook
      const title = state.catalog[chapterIndex]?.title
      if (!title) return
      return {
        name,
        author,
        durChapterIndex: chapterIndex,
        durChapterPos: chapterPos,
        durChapterTime: new Date().getTime(),
        durChapterTitle: title,
      }
    },
    theme: state => {
      return state.config.theme
    },
    isNight: state => state.config.theme == 6,
  },
  actions: {
    setDetailBook(book: Book | SeachBook | null) {
      this.detailBook = book
      if (book) {
        try {
          sessionStorage.setItem('detailBook', JSON.stringify(book))
        } catch (e) {
          console.error('[BookStore] 保存 detailBook 失败:', e)
        }
      }
    },
    async loadGroups() {
      try {
        const resp = await API.getGroups()
        const { isSuccess, data, errorMsg } = resp.data
        if (isSuccess) {
          this.groups = data
        } else {
          console.error('获取分组失败:', errorMsg)
        }
      } catch (e) {
        console.error('获取分组出错:', e)
      }
    },
    hasGroupCache(groupId?: number | string): boolean {
      const key = String(groupId ?? 'all')
      return Object.prototype.hasOwnProperty.call(this.shelfGroupCache, key)
    },
    clearShelfCache() {
      this.shelfGroupCache = {}
    },
    async loadBookShelf(groupId?: number | string, forceRefresh = false): Promise<Book[]> {
      const key = String(groupId ?? 'all')
      this.currentGroupId = groupId

      // 如果已有该分组缓存且非强制刷新，优先同步返回缓存（SWR 策略实现切换秒开）
      if (Object.prototype.hasOwnProperty.call(this.shelfGroupCache, key) && !forceRefresh) {
        this.shelf = this.shelfGroupCache[key]
        // 后台静默拉取最新数据校验更新
        this.fetchShelfData(groupId, key)
        return this.shelf
      }

      return await this.fetchShelfData(groupId, key)
    },
    async fetchShelfData(groupId: number | string | undefined, key: string): Promise<Book[]> {
      try {
        const resp = await API.getBookShelf(groupId)
        const { isSuccess, data, errorMsg } = resp.data
        if (isSuccess === true) {
          const sorted = data.sort((a: Book, b: Book) => {
            const x = a['durChapterTime'] || 0
            const y = b['durChapterTime'] || 0
            return y - x
          })
          this.shelfGroupCache[key] = sorted
          if (String(this.currentGroupId ?? 'all') === key) {
            this.shelf = sorted
          }
          return sorted
        } else {
          if (errorMsg?.includes('还没有添加小说')) {
            this.shelfGroupCache[key] = []
            if (String(this.currentGroupId ?? 'all') === key) {
              this.shelf = []
            }
            return []
          }
          toast.error(errorMsg ?? '后端返回格式错误！')
          return this.shelfGroupCache[key] || []
        }
      } catch (e) {
        console.error('fetchShelfData error:', e)
        return this.shelfGroupCache[key] || []
      }
    },
    async loadWebCatalog(
      book: typeof this.readingBook,
    ): Promise<BookChapter[]> {
      const { bookUrl, name, chapterIndex, origin } = book
      const identity = `${bookUrl}\u0000${origin || ''}`
      if (
        identity === this.catalogIdentity &&
        this.catalog.length > 0 &&
        this.catalog.length - 1 >= chapterIndex
      ) {
        console.log(`返回书籍《${name}》当前书源的缓存目录`)
        return this.catalog
      }

      const generation = ++this.catalogGeneration
      console.log(`从阅读后端获取书籍《${name}》当前书源的目录数据...`)
      const res = await API.getChapterList(book)
      const { isSuccess, data, errorMsg } = res.data
      if (!isSuccess) {
        toast.error(errorMsg)
        throw new Error(errorMsg || '获取目录失败')
      }
      if (
        generation !== this.catalogGeneration ||
        bookUrl !== this.readingBook.bookUrl ||
        (origin || '') !== (this.readingBook.origin || '')
      ) {
        throw new Error('目录请求已失效')
      }
      this.catalog = data
      this.catalogIdentity = identity
      console.log(`书籍${name}: 当前书源目录已更新`)
      return data
    },
    setContentLoading(loading: boolean) {
      this.contentLoading = loading
    },
    setReadingBook(readingBook: typeof this.readingBook) {
      this.readingBook = readingBook
    },
    async loadWebConfig() {
      if (webReadConfigLoadedDate === undefined) {
        const _config = await API.getReadConfig()
        webReadConfigLoadedDate = new Date()
        console.log(
          `${this.$id}.loadWebConfig: ${webReadConfigLoadedDate.toLocaleString()}成功加载阅读配置`,
        )
        return this.setConfig(_config)
      }
      console.log(
        `${this.$id}.loadWebConfig: 已于${webReadConfigLoadedDate.toLocaleString()}成功加载`,
      )
    },
    setConfig(config?: webReadConfig) {
      this.config = Object.assign({}, this.config, config)
    },
    setReadSettingsVisible(visible: boolean) {
      this.readSettingsVisible = visible
    },
    setShowContent(visible: boolean) {
      this.showContent = visible
    },
    setMiniInterface(mini: boolean) {
      this.miniInterface = mini
    },
    async setSearchBooks(books: SeachBook[]) {
      books.forEach(book => {
        const isSeachBook = this.shelf.every(
          item => item.bookUrl !== book.bookUrl,
        )
        if (isSeachBook === true) {
          this.searchBooks.push(book)
        }
      })
    },
    clearSearchBooks() {
      this.searchBooks = []
    },
    async saveBookProgress() {
      if (!this.bookProgress) return Promise.resolve()
      const { bookUrl } = this.readingBook
      const shelfRaw = toRaw(this.shelf)
      const findIndex = shelfRaw.findIndex(book => book.bookUrl === bookUrl)
      if (findIndex > -1) {
        this.shelf[findIndex] = Object.assign(
          {},
          shelfRaw[findIndex],
          this.bookProgress,
        )
      }
      for (const groupKey of Object.keys(this.shelfGroupCache)) {
        const list = this.shelfGroupCache[groupKey]
        const idx = list.findIndex(b => b.bookUrl === bookUrl)
        if (idx > -1) {
          list[idx] = Object.assign({}, list[idx], this.bookProgress)
        }
      }
      return API.saveBookProgressWithBeacon(this.bookProgress)
    },
  },
})
