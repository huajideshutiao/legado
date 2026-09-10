<template>
  <nav class="tab-bar" :class="isNight ? 'night' : 'day'">
    <a
      v-for="item in items"
      :key="item.tab"
      class="tab-item"
      :class="{ active: currentTab === item.tab }"
      href="javascript:void(0)"
      @click="switchTab(item.tab)"
    >
      <svg class="tab-icon" viewBox="0 0 24 24" aria-hidden="true">
        <path :d="item.icon" />
      </svg>
      <span class="tab-label">{{ item.label }}</span>
    </a>
  </nav>
</template>

<script setup lang="ts">
import { useBookStore } from '@/store'

// 对照 Android 端 MainNavItem: 图标上、12px 标签下, 选中 accent; 仅三页, 无主页
const items = [
  {
    tab: 'shelf',
    label: '书架',
    icon: 'M4 4h7v16H6a2 2 0 0 1-2-2V4zm9 0h7v14a2 2 0 0 1-2 2h-5V4zM6.5 7h2.5v1.5H6.5V7zm9 0H18v1.5h-2.5V7z',
  },
  {
    tab: 'explore',
    label: '发现',
    icon: 'M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm0 2a8 8 0 1 1 0 16 8 8 0 0 1 0-16zm4.5 3.5-2.6 6.4-6.4 2.6 2.6-6.4 6.4-2.6zM12 10.8a1.2 1.2 0 1 0 0 2.4 1.2 1.2 0 0 0 0-2.4z',
  },
  {
    tab: 'my',
    label: '我的',
    icon: 'M12 12a5 5 0 1 0 0-10 5 5 0 0 0 0 10zm0 2c-4.4 0-8 2.7-8 6v2h16v-2c0-3.3-3.6-6-8-6z',
  },
] as const

type TabKey = (typeof items)[number]['tab']

const route = useRoute()
const router = useRouter()
const store = useBookStore()
const isNight = computed(() => store.isNight)

const tabByPath: Record<string, TabKey> = {
  '/shelf': 'shelf',
  '/explore': 'explore',
  '/my': 'my',
}
const currentTab = computed(() => tabByPath[route.path] ?? '')

const switchTab = (tab: TabKey) => {
  const path = `/${tab}`
  if (route.path !== path) router.push(path)
}
</script>

<style lang="scss" scoped>
.tab-bar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 30;
  display: flex;
  height: 56px;
  border-top: 1px solid rgba(128, 128, 128, 0.2);
  padding-bottom: env(safe-area-inset-bottom);

  .tab-item {
    flex: 1;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 2px;
    text-decoration: none;
    cursor: pointer;
    color: #969ba3;

    .tab-icon {
      width: 24px;
      height: 24px;
      fill: currentColor;
    }

    .tab-label {
      font-size: 12px;
      line-height: 1;
    }

    &.active {
      color: var(--tab-accent, #1e80ff);
    }
  }

  &.day {
    background: #fff;
  }

  &.night {
    background: #454545;

    .tab-item {
      color: #8a8a8a;
    }
  }
}
</style>
