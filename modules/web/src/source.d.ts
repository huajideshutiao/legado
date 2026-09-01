/** https://github.com/gedoor/legado/tree/master/app/src/main/java/io/legado/app/data/entities */
type BaseSource = {
  /**
   * 并发率
   */
  concurrentRate?: string
  /**
   * 登录地址
   */
  loginUrl?: string

  /**
   * 登录UI
   */
  loginUi?: string

  /**
   * 请求头
   */
  header?: string

  /**
   * 启用cookieJar
   */
  enabledCookieJar?: boolean

  /**
   * js库
   */
  jsLib?: string
}
type BookSource = BaseSource & {
  // 地址，包括 http/https
  bookSourceUrl: string
  // 名称
  bookSourceName: string
  // 分组
  bookSourceGroup?: string
  // 类型，0 文本，1 音频, 2 图片, 3 文件（指的是类似知轩藏书只提供下载的网站）
  bookSourceType: number
  // 详情页url正则
  bookUrlPattern?: string
  // 手动排序编号
  customOrder: number
  // 是否启用
  enabled: boolean
  // 启用发现
  enabledExplore: boolean
  // 登录检测js
  loginCheckJs?: string
  // 封面解密js
  coverDecodeJs?: string
  // 注释
  bookSourceComment?: string
  // 自定义变量说明
  variableComment?: string
  // 最后更新时间，用于排序
  lastUpdateTime: number
  // 响应时间，用于排序
  respondTime: number
  // 智能排序的权重
  weight: number
  // 启用段评
  enabledReview?: boolean
  // 高危api
  enableDangerousApi?: boolean
  // 发现样式
  exploreStyle?: number
  // 发现url
  exploreUrl?: string
  // 发现筛选规则
  exploreScreen?: string
  // 发现规则
  ruleExplore?: ExploreRule
  // 搜索url
  searchUrl?: string
  // 搜索规则
  ruleSearch?: SearchRule
  // 书籍信息页规则
  ruleBookInfo?: BookInfoRule
  // 目录页规则
  ruleToc?: TocRule
  // 正文页规则
  ruleContent?: ContentRule
  // 段评规则
  ruleReview?: ReviewRule
}

type RawBookSource = Omit<
  BookSource,
  'ruleExplore' | 'ruleSearch' | 'ruleBookInfo' | 'ruleToc' | 'ruleContent' | 'ruleReview'
> & {
  ruleExplore?: ExploreRule | string
  ruleSearch?: SearchRule | string
  ruleBookInfo?: BookInfoRule | string
  ruleToc?: TocRule | string
  ruleContent?: ContentRule | string
  ruleReview?: ReviewRule | string
}

type SearchRule = {
  checkKeyWord?: string
  hasMoreRule?: string
  bookList?: string
  name?: string
  author?: string
  intro?: string
  kind?: string
  lastChapter?: string
  updateTime?: string
  bookUrl?: string
  coverUrl?: string
  wordCount?: string
}

type ExploreRule = {
  hasMoreRule?: string
  bookList?: string
  name?: string
  author?: string
  intro?: string
  kind?: string
  lastChapter?: string
  updateTime?: string
  bookUrl?: string
  coverUrl?: string
  wordCount?: string
}

type BookInfoRule = {
  init?: string
  name?: string
  author?: string
  intro?: string
  kind?: string
  lastChapter?: string
  updateTime?: string
  coverUrl?: string
  tocUrl?: string
  wordCount?: string
  canReName?: string
  downloadUrls?: string
}

type TocRule = {
  preUpdateJs?: string
  chapterList?: string
  chapterName?: string
  chapterUrl?: string
  isVolume?: string
  isVip?: string
  isPay?: string
  updateTime?: string
  nextTocUrl?: string
}

type ContentRule = {
  content?: string
  title?: string
  nextContentUrl?: string
  webJs?: string
  sourceRegex?: string
  replaceRegex?: string
  imageStyle?: string
  imageDecode?: string
  payAction?: string
  subContent?: string
  musicCover?: string
  shouldOverrideUrlLoading?: string
}

type ReviewRule = {
  reviewUrl?: string
  reviewList?: string
  reviewCountRule?: string
  reviewIdRule?: string
  avatarRule?: string
  nameRule?: string
  contentRule?: string
  postTimeRule?: string
  extraRule?: string
  imagesRule?: string
  voteUpCountRule?: string
  voteUpSelectedRule?: string
  voteDownSelectedRule?: string
  replyCountRule?: string
  totalCountRule?: string
  replyListUrl?: string
  hasMoreRule?: string
  voteUpRule?: string
  voteDownRule?: string
  replyRule?: string
  deleteRule?: string
}

type BookSoure = BookSource
type Source = BookSource
type RawSource = RawBookSource

export {
  Source,
  BookSource,
  BookSoure,
  RawSource,
  RawBookSource,
  SearchRule,
  ExploreRule,
  BookInfoRule,
  TocRule,
  ContentRule,
  ReviewRule,
}
