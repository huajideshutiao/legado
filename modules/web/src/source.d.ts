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
type BookSoure = BaseSource & {
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
/* type ExploreRule = {
    [prop:string]: string
}
type BookInfoRule = {
    [prop:string]: string
}
type TocRule = {
    [prop:string]: string
}
type ContentRule = {
    [prop:string]: string
}
type ReviewRule = {
    [prop:string]: string
} */
type Source = BookSoure

export { Source, BookSoure }
