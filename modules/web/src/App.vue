<template>
  <router-view v-slot="{ Component }">
    <keep-alive :include="['BookShelf', 'ExploreMain', 'MyPage', 'ExploreShow']">
      <component :is="Component" />
    </keep-alive>
  </router-view>
  <!-- 底栏三 tab (书架/发现/我的): 仅主框架页显示, 阅读页/书源编辑不显示 -->
  <TabBar v-if="showTabBar"></TabBar>
</template>

<script setup lang="ts">
import TabBar from '@/components/TabBar.vue'

const route = useRoute()
const TAB_PATHS = ['/shelf', '/explore', '/my']
const showTabBar = computed(() => TAB_PATHS.includes(route.path))
</script>
