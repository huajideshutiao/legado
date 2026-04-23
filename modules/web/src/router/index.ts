import { createWebHashHistory, createRouter } from 'vue-router'
import { bookRoutes } from './bookRouter'
import { sourceRoutes } from './sourceRouter'

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    {
      path: '/',
      name: 'welcome',
      component: () => import('../views/Welcome.vue'),
    },
    ...bookRoutes,
    ...sourceRoutes,
  ].flat(),
})

router.afterEach(to => {
  if (to.name == 'shelf') document.title = '书架'
})

export default router
