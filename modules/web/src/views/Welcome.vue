<template>
  <div class="welcome-wrapper">
    <div class="welcome-content">
      <h1 class="welcome-title">昨日邻家乞新火，晓窗分与读书灯</h1>
      <p class="welcome-subtitle">Legado Web</p>
      <nav class="welcome-nav">
        <a class="welcome-btn" @click="goTo('/shelf')" href="javascript:void(0)">书架</a>
        <a class="welcome-btn" @click="goTo('/bookSource')" href="javascript:void(0)">书源</a>
        <a class="welcome-btn" href="./uploadBook/index.html">传书</a>
      </nav>
    </div>
  </div>
</template>

<script setup lang="ts">
const router = useRouter()

const goTo = (path: string) => {
  router.push(path)
}
</script>

<style lang="scss" scoped>
.welcome-wrapper {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #1a1a2e 0%, #16213e 50%, #0f3460 100%);
}

.welcome-content {
  text-align: center;
  padding: 2em;
}

.welcome-title {
  font-size: 2em;
  font-weight: 300;
  letter-spacing: 0.1em;
  color: #fff;
  margin-bottom: 0.5em;
}

.welcome-subtitle {
  font-size: 1.1em;
  color: #a0a0c0;
  margin-bottom: 2.5em;
}

.welcome-nav {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 1em;
  max-width: 500px;
  margin: 0 auto;
}

.welcome-btn {
  flex: 1 1 0;
  min-width: 140px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0.8em 1.6em;
  border: 1px solid rgba(255, 255, 255, 0.15);
  border-radius: 6px;
  color: #e0e0e0;
  text-decoration: none;
  font-size: 1em;
  transition: all 0.2s;
  background: rgba(255, 255, 255, 0.05);
  cursor: pointer;

  &:hover {
    background: rgba(255, 255, 255, 0.12);
    border-color: rgba(255, 255, 255, 0.3);
    color: #fff;
  }
}

@media (max-width: 600px) {
  .welcome-title {
    font-size: 1.4em;
  }
  .welcome-nav {
    gap: 0.8em;
  }
  .welcome-btn {
    padding: 0.6em 1em;
    font-size: 0.9em;
  }
}
</style>
