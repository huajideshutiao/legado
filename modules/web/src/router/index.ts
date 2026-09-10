import { createWebHashHistory, createRouter } from 'vue-router'
import { bookRoutes } from './bookRouter'
import { sourceRoutes } from './sourceRouter'

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    // 直接进主界面书架 (用户裁决: 不要 Welcome 落地页)
    { path: '/', redirect: '/shelf' },
    ...bookRoutes,
    ...sourceRoutes,
  ].flat(),
})

const titleMap: Record<string, string> = {
  shelf: '书架',
  explore: '发现',
  my: '我的',
  search: '搜索',
  'source-manage': '书源管理',
  'book-home': '书源编辑',
  'explore-show': '发现',
  'book-info': '书籍详情',
  chapter: '阅读',
}

router.afterEach(to => {
  const t = titleMap[(to.name as string) || '']
  if (t) document.title = t
})

export default router
