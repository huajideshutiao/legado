import { createWebHashHistory, createRouter } from 'vue-router'

export const bookRoutes = [
  {
    path: '/shelf',
    name: 'shelf',
    component: () => import('../views/BookShelf.vue'),
  },
  {
    path: '/explore',
    name: 'explore',
    component: () => import('../views/ExploreMain.vue'),
  },
  {
    path: '/my',
    name: 'my',
    component: () => import('../views/MyPage.vue'),
  },
  {
    path: '/chapter',
    name: 'chapter',
    component: () => import('../views/BookChapter.vue'),
  },
  {
    path: '/search',
    name: 'search',
    component: () => import('../views/SearchBook.vue'),
  },
  {
    path: '/explore-show',
    name: 'explore-show',
    component: () => import('../views/ExploreShow.vue'),
  },
  {
    path: '/book-info',
    name: 'book-info',
    component: () => import('../views/BookInfo.vue'),
  },
]

const router = createRouter({
  history: createWebHashHistory(),
  routes: bookRoutes,
})

export default router
