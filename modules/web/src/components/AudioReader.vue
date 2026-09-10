<template>
  <div class="audio-reader" @click="handleAudioClick">
    <!-- 隐藏的原生 audio 元素 -->
    <audio
      ref="audioRef"
      :src="currentAudioSrc"
      preload="auto"
      @play="onPlay"
      @pause="onPause"
      @timeupdate="onTimeUpdate"
      @loadedmetadata="onLoadedMetadata"
      @ended="onEnded"
      @error="onError"
    ></audio>

    <div class="audio-card">
      <!-- 纯净圆形封面展示区 -->
      <div class="audio-cover-wrapper">
        <img
          v-if="coverImageSrc"
          :src="coverImageSrc"
          class="audio-cover-img"
          alt="封面"
          @error="onCoverError"
        />
        <div v-else class="audio-cover-placeholder">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" width="56" height="56">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M9 19V6l12-3v13M9 19c0 1.105-1.343 2-3 2s-3-.895-3-2 1.343-2 3-2 3 .895 3 2zm12-3c0 1.105-1.343 2-3 2s-3-.895-3-2 1.343-2 3-2 3 .895 3 2zM9 10l12-3" />
          </svg>
        </div>
      </div>

      <!-- 书籍与章节信息 -->
      <div class="audio-info">
        <div class="book-name-label">《{{ bookName }}》</div>
        <div class="chapter-title-label">{{ title }}</div>

        <!-- 错误提示 -->
        <div v-if="audioErrorMsg" class="audio-error-box">
          <div class="audio-error-hint">{{ audioErrorMsg }}</div>
        </div>
      </div>

      <!-- 进度条与时间显示 -->
      <div class="progress-section">
        <div class="progress-bar-box">
          <input
            type="range"
            class="progress-slider"
            min="0"
            :max="duration || 100"
            :value="currentTime"
            :disabled="!duration"
            @input="onSliderInput"
            @change="onSliderChange"
          />
        </div>
        <div class="time-display">
          <span class="curr-time">{{ formatTime(currentTime) }}</span>
          <span class="total-time">{{ formatTime(duration) }}</span>
        </div>
      </div>

      <!-- 核心播放控制按钮栏 -->
      <div class="controls-section">
        <!-- 上一章 -->
        <button
          class="ctrl-btn sub-btn"
          title="上一章"
          :disabled="chapterIndex <= 0"
          @click="emit('prevChapter')"
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" width="24" height="24">
            <polygon stroke-width="2" stroke-linecap="round" stroke-linejoin="round" points="19 20 9 12 19 4 19 20" />
            <line stroke-width="2" stroke-linecap="round" x1="5" y1="19" x2="5" y2="5" />
          </svg>
        </button>

        <!-- 播放/暂停 -->
        <button class="ctrl-btn play-btn" :title="isPlaying ? '暂停' : '播放'" @click="togglePlay">
          <svg v-if="!isPlaying" viewBox="0 0 24 24" fill="currentColor" width="30" height="30">
            <polygon points="6 4 20 12 6 20 6 4" />
          </svg>
          <svg v-else viewBox="0 0 24 24" fill="currentColor" width="28" height="28">
            <rect x="6" y="5" width="4" height="14" rx="1" />
            <rect x="14" y="5" width="4" height="14" rx="1" />
          </svg>
        </button>

        <!-- 下一章 -->
        <button
          class="ctrl-btn sub-btn"
          title="下一章"
          :disabled="catalog && chapterIndex >= catalog.length - 1"
          @click="emit('nextChapter')"
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" width="24" height="24">
            <polygon stroke-width="2" stroke-linecap="round" stroke-linejoin="round" points="5 4 15 12 5 20 5 4" />
            <line stroke-width="2" stroke-linecap="round" x1="19" y1="5" x2="19" y2="19" />
          </svg>
        </button>
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
  author?: string
  coverUrl?: string
  origin?: string
  catalog?: BookChapter[]
  initialPos?: number
}>()

const emit = defineEmits<{
  (e: 'prevChapter'): void
  (e: 'nextChapter'): void
  (e: 'saveProgress', pos: number, chapterIndex: number): void
  (e: 'reParse'): void
  (e: 'toggleToolbar'): void
}>()

const handleAudioClick = (e: MouseEvent) => {
  const target = e.target as HTMLElement | null
  if (!target) return
  if (
    target.closest(
      'button, input, select, textarea, a, .progress-section, .controls-section'
    )
  ) {
    return
  }
  emit('toggleToolbar')
}

const audioRef = ref<HTMLAudioElement>()
const isPlaying = ref(false)
const isSwitchingSource = ref(false)
const currentTime = ref(0)
const duration = ref(0)
const currentAudioSrc = ref('')
const audioErrorMsg = ref('')
const isReParsing = ref(false)
const hasRefreshedOnPlayError = ref(false)

const reParseContent = () => {
  audioErrorMsg.value = ''
  isReParsing.value = true
  emit('reParse')
}


// 封面图片解析与代理
const coverImageSrc = computed(() => {
  if (!props.coverUrl) return ''
  return API.getProxyCoverUrl(props.coverUrl)
})

const onCoverError = (e: Event) => {
  if ((e.target as HTMLImageElement).src !== props.coverUrl && props.coverUrl) {
    (e.target as HTMLImageElement).src = props.coverUrl
  }
}

// 清洗 URL 参数
const cleanMediaUrl = (raw: string): string => {
  if (!raw) return ''
  let str = raw.trim()
  const commaIdx = str.indexOf(',{')
  if (commaIdx > 0) {
    str = str.substring(0, commaIdx).trim()
  }
  return str
}

// 解析音频 URL：优先取 chapter.resourceUrl，其次匹配 contents 中的链接
const extractAudioUrl = (): string => {
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
      /\.(mp3|m4a|aac|wav|flac|ogg|opus|m4s)(\?|$)/i.test(line)
    ) {
      return line
    }
  }

  // 兜底匹配 http
  const match = text.match(/https?:\/\/[^\s"'<>]+/i)
  if (match) {
    return cleanMediaUrl(match[0])
  }

  return ''
}

const loadAudioSource = () => {
  audioErrorMsg.value = ''
  isReParsing.value = false
  const url = extractAudioUrl()

  if (!url) {
    isSwitchingSource.value = false
    const text = Array.isArray(props.contents) ? props.contents.join('\n') : (props.contents || '')
    const hasValidContent = text.trim().length > 0
    if (hasValidContent && !hasRefreshedOnPlayError.value) {
      hasRefreshedOnPlayError.value = true
      console.info('[AudioReader] 正文已加载但未检测到音频资源，尝试刷新正文一次...')
      reParseContent()
      return
    }
    if (hasValidContent) {
      audioErrorMsg.value = '未找到音频播放资源'
    }
    currentAudioSrc.value = ''
    return
  }

  currentAudioSrc.value = API.getMediaStreamUrl(url, props.origin)
  if (!currentAudioSrc.value) {
    isSwitchingSource.value = false
    audioErrorMsg.value = '音频地址无效，仅支持 http、https、data 或 blob 直连地址'
  }
}

watch(
  () => props.chapterIndex,
  (newIdx, oldIdx) => {
    hasRefreshedOnPlayError.value = false
    if (newIdx !== oldIdx) {
      isSwitchingSource.value = true
    }
  }
)

watch(
  () => [props.chapterIndex, props.contents],
  () => {
    loadAudioSource()
  },
  { immediate: true, deep: true }
)

const togglePlay = () => {
  const audio = audioRef.value
  if (!audio) return
  if (audio.paused) {
    audio.play().catch(err => {
      console.warn('[AudioReader] 播放出错:', err)
      audioErrorMsg.value = '播放失败，请检查音频源或网络'
    })
  } else {
    audio.pause()
  }
}

const onPlay = () => {
  isPlaying.value = true
  audioErrorMsg.value = ''
}

const onPause = () => {
  isPlaying.value = false
  if (isSwitchingSource.value) return
  emit('saveProgress', Math.floor(currentTime.value), props.chapterIndex)
}

const onTimeUpdate = () => {
  if (!audioRef.value) return
  currentTime.value = audioRef.value.currentTime
}

const onLoadedMetadata = () => {
  isSwitchingSource.value = false
  if (!audioRef.value) return
  duration.value = audioRef.value.duration || 0

  // 恢复历史阅读进度
  if (props.initialPos && props.initialPos > 0 && props.initialPos < duration.value) {
    audioRef.value.currentTime = props.initialPos
  }

  // 自动播放
  audioRef.value.play().catch(() => {
    // 浏览器可能拦截无手势自动播放，降级等待用户点击
    isPlaying.value = false
  })
}

const onEnded = () => {
  isPlaying.value = false
  emit('saveProgress', Math.floor(duration.value || currentTime.value), props.chapterIndex)
  // 当前章节播放完毕，自动切至下一章
  emit('nextChapter')
}

const onError = () => {
  isSwitchingSource.value = false

  // 对照 Kt 端 AudioPlaySession.onPlayerError：resourceUrl 多半过期，清掉后重新解析一次。
  if (!hasRefreshedOnPlayError.value) {
    hasRefreshedOnPlayError.value = true
    console.info('[AudioReader] 音频播放首错，自动重新解析正文内容...')
    reParseContent()
    return
  }

  audioErrorMsg.value = '音频直连加载失败，当前资源可能受 CORS 跨域或 Referer 防盗链限制，请重试或切换书源'
  isPlaying.value = false
}

const onSliderInput = (e: Event) => {
  currentTime.value = Number((e.target as HTMLInputElement).value)
}

const onSliderChange = (e: Event) => {
  const target = Number((e.target as HTMLInputElement).value)
  if (audioRef.value) {
    audioRef.value.currentTime = target
  }
  emit('saveProgress', Math.floor(target), props.chapterIndex)
}


const formatTime = (secs: number): string => {
  if (!secs || isNaN(secs)) return '00:00'
  const s = Math.floor(secs)
  const m = Math.floor(s / 60)
  const sec = s % 60
  if (m >= 60) {
    const h = Math.floor(m / 60)
    const min = m % 60
    return `${h < 10 ? '0' + h : h}:${min < 10 ? '0' + min : min}:${sec < 10 ? '0' + sec : sec}`
  }
  return `${m < 10 ? '0' + m : m}:${sec < 10 ? '0' + sec : sec}`
}

// 定时保存进度
let progressTimer: number | null = null
onMounted(() => {
  progressTimer = window.setInterval(() => {
    if (isPlaying.value && !isSwitchingSource.value && currentTime.value > 0) {
      emit('saveProgress', Math.floor(currentTime.value), props.chapterIndex)
    }
  }, 15000)
})

onUnmounted(() => {
  if (progressTimer) clearInterval(progressTimer)
  isSwitchingSource.value = true
  if (audioRef.value) {
    audioRef.value.pause()
  }
})
</script>

<style lang="scss" scoped>
.audio-reader {
  width: 100%;
  min-height: calc(100vh - 100px);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 32px 16px;
  box-sizing: border-box;
  user-select: none;
  cursor: pointer;

  .audio-card {
    width: 100%;
    max-width: 520px;
    margin: 0 auto;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    box-sizing: border-box;

    .audio-cover-wrapper {
      width: min(240px, 60vw);
      height: min(240px, 60vw);
      border-radius: 50%;
      overflow: hidden;
      box-shadow: 0 8px 30px rgba(0, 0, 0, 0.18);
      background: rgba(128, 128, 128, 0.1);
      display: flex;
      align-items: center;
      justify-content: center;
      margin: 10px 0 32px;
      flex-shrink: 0;

      .audio-cover-img {
        width: 100%;
        height: 100%;
        object-fit: cover;
        display: block;
      }

      .audio-cover-placeholder {
        color: rgba(128, 128, 128, 0.5);
        display: flex;
        align-items: center;
        justify-content: center;
      }
    }

    .audio-info {
      width: 100%;
      text-align: center;
      margin-bottom: 28px;

      .book-name-label {
        font-size: 15px;
        opacity: 0.7;
        margin-bottom: 8px;
      }

      .chapter-title-label {
        font-size: 20px;
        font-weight: 600;
        line-height: 1.4;
        color: inherit;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }

      .audio-error-box {
        margin-top: 12px;
        display: flex;
        flex-direction: column;
        align-items: center;

        .audio-error-hint {
          font-size: 13px;
          color: #e53935;
        }
      }
    }

    .progress-section {
      width: 100%;
      margin-bottom: 28px;

      .progress-bar-box {
        width: 100%;

        .progress-slider {
          width: 100%;
          height: 6px;
          border-radius: 3px;
          outline: none;
          cursor: pointer;
          accent-color: #2196f3;
        }
      }

      .time-display {
        display: flex;
        justify-content: space-between;
        margin-top: 8px;
        font-size: 13px;
        opacity: 0.6;
      }
    }

    .controls-section {
      width: 100%;
      margin: 0 auto;
      display: flex;
      align-items: center;
      justify-content: center;
      gap: 18px;

      .ctrl-btn {
        padding: 0;
        margin: 0;
        box-sizing: border-box;
        background: transparent;
        border: none;
        color: inherit;
        cursor: pointer;
        display: inline-flex;
        align-items: center;
        justify-content: center;
        border-radius: 50%;
        transition: all 0.2s ease;
        outline: none;

        &:disabled {
          opacity: 0.3;
          cursor: not-allowed;
        }

        &.sub-btn {
          width: 48px;
          height: 48px;
          opacity: 0.8;

          &:hover:not(:disabled) {
            opacity: 1;
            background: rgba(128, 128, 128, 0.15);
          }
        }

        &.play-btn {
          width: 66px;
          height: 66px;
          background: #2196f3;
          color: #ffffff;
          box-shadow: 0 4px 16px rgba(33, 150, 243, 0.4);

          &:hover {
            transform: scale(1.05);
            background: #1976d2;
          }

          &:active {
            transform: scale(0.98);
          }
        }
      }
    }
  }
}

.spin {
  animation: spin-icon 1s linear infinite;
}

@keyframes spin-icon {
  from {
    transform: rotate(0deg);
  }
  to {
    transform: rotate(360deg);
  }
}
</style>
