<template>
  <div :class="{ 'index-wrapper': true, night: isNight, day: !isNight }">
    <div class="navigation-wrapper">
      <div class="navigation-title-wrapper">
        <div class="navigation-title">阅读</div>
        <div class="navigation-sub-title">清风不识字，何故乱翻书</div>
      </div>
      <div class="search-wrapper">
        <input
          class="web-input search-input"
          placeholder="搜索书籍，在线书籍自动加入书架"
          v-model="searchWord"
          @keyup.enter="searchBook"
        />
      </div>
      <div class="bottom-wrapper">
        <div class="recent-wrapper">
          <div class="recent-title">最近阅读</div>
          <div class="reading-recent">
            <span
              class="web-tag"
              :class="[readingRecent.name === '尚无阅读记录' ? 'web-tag--warning' : 'web-tag--primary', 'web-tag--large', 'recent-book', { 'no-point': !readingRecent.bookUrl }]"
              @click="
                toDetail(
                  readingRecent.bookUrl,
                  readingRecent.name,
                  readingRecent.author,
                  readingRecent.chapterIndex,
                  readingRecent.chapterPos,
                  readingRecent.isSeachBook,
                  true,
                )
              "
            >
              {{ readingRecent.name }}
            </span>
          </div>
        </div>
        <div class="group-wrapper">
          <div class="group-title">书架分组</div>
          <div class="group-selector">
            <select
              v-model="currentGroupId"
              class="web-select group-select"
              @change="handleGroupChange($event)"
            >
              <option
                v-for="group in groups"
                :key="group.groupId"
                :value="group.groupId"
              >
                {{ group.groupName }}
              </option>
            </select>
          </div>
        </div>
        <div class="setting-wrapper">
          <div class="setting-title">基本设定</div>
          <div class="setting-item">
            <span
              class="web-tag"
              :class="[connectType === 'primary' ? 'web-tag--success' : 'web-tag--danger', 'web-tag--large', 'setting-connect', { 'no-point': newConnect }]"
              @click="setLegadoRetmoteUrl"
            >
              {{ connectStatus }}
            </span>
          </div>
        </div>
      </div>
      <div class="bottom-icons">
        <a
          href="https://github.com/gedoor/legado_web_bookshelf"
          target="_blank"
        >
          <div class="bottom-icon">
            <img :src="githubUrl" alt="" />
          </div>
        </a>
      </div>
    </div>
    <div class="shelf-wrapper" ref="shelfWrapper">
      <book-items
        :books="books"
        @bookClick="handleBookClick"
        :isSearch="isSearching"
      ></book-items>
    </div>
  </div>
</template>

<script setup lang="ts">
import '@/assets/bookshelf.css'
import '@/assets/fonts/shelffont.css'
import { useBookStore } from '@/store'
import githubUrl from '@/assets/imgs/github.png'
import { useLoading } from '@/hooks/loading'
import { baseURL_localStorage_key } from '@/api/axios'
import API, {
  legado_http_entry_point,
  parseLeagdoHttpUrlWithDefault,
  setApiEntryPoint,
} from '@api'
import { validatorHttpUrl } from '@/utils/utils'
import { toast } from '@/utils/toast'
import { msgbox } from '@/utils/toast'
import type { Book, SeachBook } from '@/book'
import type { webReadConfig } from '@/web'

const store = useBookStore()
const isNight = computed(() => store.isNight)

const applyReadConfig = (config?: webReadConfig) => {
  try {
    if (config !== undefined) store.setConfig(config)
  } catch {
    toast.info('阅读界面配置解析错误')
  }
}

const readingRecent = ref<typeof store.readingBook>({
  name: '尚无阅读记录',
  author: '',
  bookUrl: '',
  chapterIndex: 0,
  chapterPos: 0,
  isSeachBook: false,
})

const shelfWrapper = ref<HTMLElement>()
const { showLoading, closeLoading, loadingWrapper, isLoading } = useLoading(
  shelfWrapper,
  '正在获取书籍信息',
)

const books = shallowRef<Book[] | SeachBook[]>([])
const shelf = computed(() => store.shelf)
const searchWord = ref('')
const isSearching = ref(false)

const groups = computed(() => store.groups)
const currentGroupId = ref<number | string | undefined>(undefined)
watchEffect(() => {
  if (isSearching.value && searchWord.value != '') return
  isSearching.value = false
  books.value = []
  if (searchWord.value == '') {
    books.value = shelf.value
    return
  }
  books.value = shelf.value.filter(book => {
    return (
      book.name.includes(searchWord.value) ||
      book.author.includes(searchWord.value)
    )
  })
})

const searchBook = () => {
  if (searchWord.value == '') return
  books.value = []
  store.clearSearchBooks()
  showLoading()
  isSearching.value = true
  API.search(
    searchWord.value,
    searcBooks => {
      if (isLoading) {
        closeLoading()
      }
      try {
        store.setSearchBooks(searcBooks)
        books.value = store.searchBooks
      } catch (e) {
        toast.error('后端数据错误')
        throw e
      }
    },
    () => {
      closeLoading()
      if (books.value.length == 0) {
        toast.info('搜索结果为空')
      }
    },
  )
}

const connectionStore = useConnectionStore()
const { connectStatus, connectType, newConnect } = storeToRefs(connectionStore)

const setLegadoRetmoteUrl = () => {
  msgbox.prompt(
    '请输入 后端地址 ( 如：http://127.0.0.1:9527 或者通过内网穿透的地址)',
    '提示',
    {
      inputPlaceholder: legado_http_entry_point,
      inputValidator: value => validatorHttpUrl(value) as boolean,
      inputErrorMessage: '输入的格式不对',
    },
  ).then(async (result) => {
    if (result.action === 'confirm') {
      connectionStore.setNewConnect(true)
      const url = new URL(result.value).toString()
      try {
        const config = await API.getReadConfig(url)
        connectionStore.setNewConnect(false)
        applyReadConfig(config)
        store.clearSearchBooks()
        setApiEntryPoint(...parseLeagdoHttpUrlWithDefault(url))
        if (url === location.origin) {
          localStorage.removeItem(baseURL_localStorage_key)
        } else {
          localStorage.setItem(baseURL_localStorage_key, url)
        }
        store.loadBookShelf()
      } catch (error) {
        connectionStore.setNewConnect(false)
        throw error
      }
    }
  })
}

const router = useRouter()
const handleBookClick = async (book: SeachBook | Book) => {
  const isSeachBook = 'respondTime' in book
  if (isSeachBook) {
    await API.saveBook(book)
  }
  const {
    bookUrl,
    name,
    author,
    durChapterIndex = 0,
    durChapterPos = 0,
  } = book as any

  toDetail(bookUrl, name, author, durChapterIndex, durChapterPos, isSeachBook)
}
const toDetail = (
  bookUrl: string,
  bookName: string,
  bookAuthor: string,
  chapterIndex: number,
  chapterPos: number,
  isSeachBook: boolean | undefined = false,
  fromReadRecentClick = false,
) => {
  if (bookName === '尚无阅读记录') return
  if (
    fromReadRecentClick &&
    shelf.value.every(book => book.bookUrl !== bookUrl)
  ) {
    searchWord.value = bookName
    searchBook()
    return
  }
  sessionStorage.setItem('bookUrl', bookUrl)
  sessionStorage.setItem('bookName', bookName)
  sessionStorage.setItem('bookAuthor', bookAuthor)
  sessionStorage.setItem('chapterIndex', String(chapterIndex))
  sessionStorage.setItem('chapterPos', String(chapterPos))
  sessionStorage.setItem('isSeachBook', String(isSeachBook))
  readingRecent.value = {
    name: bookName,
    author: bookAuthor,
    bookUrl,
    chapterIndex,
    chapterPos,
    isSeachBook,
  }
  localStorage.setItem('readingRecent', JSON.stringify(readingRecent.value))
  router.push({
    path: '/chapter',
  })
}

const loadShelf = async () => {
  await store.loadWebConfig()
  await store.saveBookProgress()
  await store.loadGroups()
  if (groups.value.length > 0) {
    currentGroupId.value = groups.value[0].groupId
    await store.loadBookShelf(currentGroupId.value)
  } else {
    await store.loadBookShelf()
  }
}

const handleGroupChange = async (e: Event) => {
  const groupId = (e.target as HTMLSelectElement).value
  showLoading()
  try {
    await store.loadBookShelf(groupId)
  } finally {
    closeLoading()
  }
}

onMounted(() => {
  const readingRecentStr = localStorage.getItem('readingRecent')
  if (readingRecentStr != null) {
    readingRecent.value = JSON.parse(readingRecentStr)
    if (typeof readingRecent.value.chapterIndex == 'undefined') {
      readingRecent.value.chapterIndex = 0
    }
  }
  console.log('bookshelf mounted')
  loadingWrapper(loadShelf())
})
</script>

<style lang="scss" scoped>
.index-wrapper {
  height: 100%;
  width: 100%;
  display: flex;
  flex-direction: row;

  .navigation-wrapper {
    width: 260px;
    min-width: 260px;
    padding: 48px 36px;
    background-color: #f7f7f7;

    .navigation-title {
      font-size: 24px;
      font-weight: 500;
      font-family: FZZCYSK;
    }

    .navigation-sub-title {
      font-size: 16px;
      font-weight: 300;
      font-family: FZZCYSK;
      margin-top: 16px;
      color: #b1b1b1;
    }

    .search-wrapper {
      .search-input {
        border-radius: 50px;
        margin-top: 24px;
        border-color: #e3e3e3;
      }
    }

    .bottom-wrapper {
      display: flex;
      flex-direction: column;
    }

    .group-wrapper {
      margin-top: 36px;

      .group-title {
        font-size: 14px;
        color: #b1b1b1;
        font-family: FZZCYSK;
      }

      .group-selector {
        margin: 18px 0;

        .group-select {
          width: 100%;
        }
      }
    }

    .recent-wrapper {
      margin-top: 36px;

      .recent-title {
        font-size: 14px;
        color: #b1b1b1;
        font-family: FZZCYSK;
      }

      .reading-recent {
        margin: 18px 0;

        .recent-book {
          cursor: pointer;
        }
      }
    }

    .setting-wrapper {
      margin-top: 36px;

      .setting-title {
        font-size: 14px;
        color: #b1b1b1;
        font-family: FZZCYSK;
      }

      .no-point {
        pointer-events: none;
      }

      .setting-connect {
        margin-top: 16px;
        cursor: pointer;
      }
    }

    .bottom-icons {
      position: fixed;
      bottom: 0;
      height: 120px;
      width: 260px;
      align-items: center;
      display: flex;
      flex-direction: row;
    }
  }

  .shelf-wrapper {
    padding: 48px 48px;
    width: 100%;
    display: flex;
    flex-direction: column;
    box-sizing: border-box;
    overflow: hidden;
  }
}

@media screen and (max-width: 750px) {
  .index-wrapper {
    overflow-x: hidden;
    flex-direction: column;

    .navigation-wrapper {
      padding: 20px 24px;
      box-sizing: border-box;
      width: 100%;

      .navigation-title-wrapper {
        white-space: nowrap;
        display: flex;
        justify-content: space-between;
        align-items: flex-end;
      }

      .bottom-wrapper {
        flex-direction: row;

        > * {
          flex-grow: 1;
          margin-top: 18px;

          .reading-recent,
          .group-selector,
          .setting-item {
            margin-bottom: 0px;
          }
        }
      }

      .bottom-icons {
        display: none;
      }
    }

    .shelf-wrapper {
      padding: 0;
      flex-grow: 1;
    }
  }
}

.night {
  .navigation-wrapper {
    background-color: #454545;

    .navigation-title {
      color: #aeaeae;
    }

    .search-wrapper {
      .search-input {
        background-color: #454545;
        color: #b1b1b1;
        border-color: #555;
      }
    }
  }

  .shelf-wrapper {
    background-color: #161819;
  }
}
</style>
