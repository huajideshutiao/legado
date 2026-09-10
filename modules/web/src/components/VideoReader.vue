<template>
  <div class="video-reader" @click="handleVideoClick">
    <div class="video-card">
      <!-- 原生 Video 播放容器 -->
      <div class="video-player-box">
        <video
          ref="videoRef"
          :src="currentVideoSrc"
          class="video-element"
          controls
          playsinline
          webkit-playsinline
          preload="auto"
          @ended="onVideoEnded"
          @error="onVideoError"
        ></video>

        <!-- 错误提示与直连/重新解析重试 -->
        <div v-if="videoError" class="video-error-overlay">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" width="40" height="40">
            <circle cx="12" cy="12" r="10" stroke-width="2" />
            <line x1="12" y1="8" x2="12" y2="12" stroke-width="2" stroke-linecap="round" />
            <line x1="12" y1="16" x2="12.01" y2="16" stroke-width="2" stroke-linecap="round" />
          </svg>
          <p>{{ errorMsg || '视频加载失败，请检查视频源地址' }}</p>
          <div class="error-action-row">
            <button class="retry-video-btn" @click="retryLoad">重试播放</button>
            <button class="reparse-video-btn" @click="emit('reParse')">刷新正文</button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import API from '@api'
import type { BookChapter } from '@/book'

const props = defineProps<{
  chapterIndex: number
  title: string
  contents: string[] | string
  bookName: string
  origin?: string
  catalog?: BookChapter[]
}>()

const emit = defineEmits<{
  (e: 'prevChapter'): void
  (e: 'nextChapter'): void
  (e: 'reParse'): void
  (e: 'toggleToolbar'): void
}>()

const handleVideoClick = (e: MouseEvent) => {
  const target = e.target as HTMLElement | null
  if (!target) return
  if (target.closest('button, input, textarea, select, a, video, .video-error-overlay')) {
    return
  }
  emit('toggleToolbar')
}

const videoRef = ref<HTMLVideoElement>()
const currentVideoSrc = ref('')
const videoError = ref(false)
const errorMsg = ref('')

const cleanMediaUrl = (raw: string): string => {
  if (!raw) return ''
  let str = raw.trim()
  const commaIdx = str.indexOf(',{')
  if (commaIdx > 0) {
    str = str.substring(0, commaIdx).trim()
  }
  return str
}

// 解析视频链接：优先取 chapter.resourceUrl，其次匹配 contents 中的首个 URL
const extractVideoUrl = (): string => {
  if (props.catalog && props.catalog[props.chapterIndex]?.resourceUrl) {
    const resUrl = cleanMediaUrl(props.catalog[props.chapterIndex].resourceUrl!)
    if (resUrl) return resUrl
  }

  const text = Array.isArray(props.contents) ? props.contents.join('\n') : (props.contents || '')
  if (!text) return ''

  const lines = text.split('\n').map(l => cleanMediaUrl(l)).filter(Boolean)
  for (const line of lines) {
    if (
      /^https?:\/\//i.test(line) ||
      /\.(mp4|m3u8|webm|ogg|flv|mov|mkv)(\?|$)/i.test(line)
    ) {
      return line
    }
  }

  const match = text.match(/https?:\/\/[^\s"'<>]+/i)
  if (match) {
    return cleanMediaUrl(match[0])
  }

  return ''
}

const loadVideoSource = () => {
  videoError.value = false
  errorMsg.value = ''

  const url = extractVideoUrl()

  if (!url) {
    videoError.value = true
    errorMsg.value = '本集暂无可播放的视频源'
    currentVideoSrc.value = ''
    return
  }

  currentVideoSrc.value = API.getMediaStreamUrl(url, props.origin)
  if (!currentVideoSrc.value) {
    videoError.value = true
    errorMsg.value = '视频地址无效，仅支持 http、https、data 或 blob 直连地址'
  }
}

watch(
  () => [props.chapterIndex, props.contents],
  () => {
    loadVideoSource()
  },
  { immediate: true, deep: true }
)

const onVideoEnded = () => {
  // 视频播放完成自动切换下一集
  emit('nextChapter')
}

const onVideoError = () => {
  videoError.value = true
  errorMsg.value = '视频直连加载失败，当前资源可能受 CORS 跨域或 Referer 防盗链限制，请刷新正文或切换书源'
}

const retryLoad = () => {
  videoError.value = false
  loadVideoSource()
}

onUnmounted(() => {
  if (videoRef.value) {
    videoRef.value.pause()
  }
})
</script>

<style lang="scss" scoped>
.video-reader {
  width: 100%;
  box-sizing: border-box;
  display: flex;
  justify-content: center;
  align-items: center;
  padding: 0;

  .video-card {
    width: 100%;
    max-width: 100%;
    display: flex;
    flex-direction: column;

    .video-player-box {
      position: relative;
      width: 100%;
      height: calc(100vh - 24px);
      min-height: 360px;
      max-height: 94vh;
      background: #000000;
      overflow: hidden;

      .video-element {
        width: 100%;
        height: 100%;
        display: block;
        object-fit: contain;
      }

      .video-error-overlay {
        position: absolute;
        inset: 0;
        display: flex;
        flex-direction: column;
        align-items: center;
        justify-content: center;
        background: rgba(0, 0, 0, 0.85);
        color: #ffffff;
        gap: 16px;
        padding: 20px;
        text-align: center;

        p {
          margin: 0;
          font-size: 15px;
          opacity: 0.9;
        }

        .error-action-row {
          display: flex;
          gap: 12px;
          align-items: center;
        }

        .retry-video-btn,
        .reparse-video-btn {
          padding: 8px 20px;
          border-radius: 6px;
          border: 1px solid rgba(255, 255, 255, 0.3);
          background: rgba(255, 255, 255, 0.15);
          color: #ffffff;
          cursor: pointer;
          font-size: 13px;
          transition: background 0.2s;

          &:hover {
            background: rgba(255, 255, 255, 0.25);
          }
        }
      }
    }
  }
}
</style>
