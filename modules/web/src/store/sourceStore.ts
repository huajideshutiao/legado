import { defineStore } from 'pinia'
import {
  emptyBookSource,
  getSourceUniqueKey,
  convertSourcesToMap,
  ensureSourceRules,
} from '@utils/souce'
import type { BookSource, RawSource, Source } from '@/source'

const emptySource = emptyBookSource

export const useSourceStore = defineStore('source', {
  state: () => {
    return {
      bookSources: shallowRef([] as Source[]),
      savedSources: [] as Source[],
      currentSource: ensureSourceRules(JSON.parse(JSON.stringify(emptySource))),
      currentTab: localStorage.getItem('tabName') || 'editTab',
      editTabSource: {} as Source,
      isDebuging: false,
    }
  },
  getters: {
    sources: (state): Source[] => state.bookSources,
    sourcesMap: function (): Map<string, Source> {
      return convertSourcesToMap(this.sources)
    },
    savedSourcesMap: (state): Map<string, Source> =>
      convertSourcesToMap(state.savedSources),
    currentSourceUrl: state =>
      state.currentSource.bookSourceUrl,
    searchKey: (state): string =>
      state.currentSource?.ruleSearch?.checkKeyWord || '我的',
  },
  actions: {
    startDebug() {
      this.currentTab = 'editDebug'
      this.isDebuging = true
    },
    debugFinish() {
      this.isDebuging = false
    },

    saveSources(data: (RawSource | Source)[] | RawSource | Source) {
      const arr = Array.isArray(data) ? data : data ? [data] : []
      const normalized = arr.map(s => ensureSourceRules(s))
      this.bookSources = markRaw(normalized)
    },
    setPushReturnSources(returnSoures: (RawSource | Source)[]) {
      this.savedSources = returnSoures.map(s => ensureSourceRules(s))
    },
    deleteSources(data: (RawSource | Source)[]) {
      data.forEach(source => {
        const uniqueKey = getSourceUniqueKey(source)
        const index = this.bookSources.findIndex(s => getSourceUniqueKey(s) === uniqueKey)
        if (index > -1) this.bookSources.splice(index, 1)
      })
    },
    saveCurrentSource() {
      const source = this.currentSource,
        map = this.sourcesMap
      map.set(getSourceUniqueKey(source), JSON.parse(JSON.stringify(source)))
      this.saveSources(Array.from(map.values()))
    },
    changeCurrentSource(source: RawSource | Source) {
      this.currentSource = ensureSourceRules(JSON.parse(JSON.stringify(source)))
    },
    changeTabName(tabName: string) {
      this.currentTab = tabName
      localStorage.setItem('tabName', tabName)
    },
    changeEditTabSource(source: RawSource | Source) {
      this.editTabSource = ensureSourceRules(JSON.parse(JSON.stringify(source)))
    },
    editHistory(history: RawSource | Source) {
      let historyObj
      const normalizedHistory = ensureSourceRules(history)
      if (localStorage.getItem('history')) {
        historyObj = JSON.parse(localStorage.getItem('history')!)
        historyObj.new.push(normalizedHistory)
        if (historyObj.new.length > 50) {
          historyObj.new.shift()
        }
        if (historyObj.old.length > 50) {
          historyObj.old.shift()
        }
        localStorage.setItem('history', JSON.stringify(historyObj))
      } else {
        const arr = { new: [normalizedHistory], old: [] }
        localStorage.setItem('history', JSON.stringify(arr))
      }
    },
    editHistoryUndo() {
      if (localStorage.getItem('history')) {
        const historyObj = JSON.parse(localStorage.getItem('history')!)
        historyObj.old.push(this.currentSource)
        if (historyObj.new.length) {
          this.currentSource = historyObj.new.pop()
        }
        localStorage.setItem('history', JSON.stringify(historyObj))
      }
    },
    clearAllHistory() {
      localStorage.setItem('history', JSON.stringify({ new: [], old: [] }))
    },
    clearEdit() {
      this.editTabSource = {} as Source
      this.currentSource = JSON.parse(JSON.stringify(emptySource))
    },

    clearAllSource() {
      this.bookSources = []
      this.savedSources = []
    },
  },
})
