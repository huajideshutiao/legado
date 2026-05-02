import { defineStore } from 'pinia'
import {
  emptyBookSource,
  getSourceUniqueKey,
  convertSourcesToMap,
} from '@utils/souce'
import type { BookSoure, Source } from '@/source'

const emptySource = emptyBookSource

export const useSourceStore = defineStore('source', {
  state: () => {
    return {
      bookSources: shallowRef([] as BookSoure[]),
      savedSources: [] as Source[],
      currentSource: JSON.parse(JSON.stringify(emptySource)) as Source,
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
      (state.currentSource as BookSoure).bookSourceUrl,
    searchKey: (state): string =>
      (state.currentSource as BookSoure)?.ruleSearch?.checkKeyWord || '我的',
  },
  actions: {
    startDebug() {
      this.currentTab = 'editDebug'
      this.isDebuging = true
    },
    debugFinish() {
      this.isDebuging = false
    },

    saveSources(data: Source[]) {
      this.bookSources = markRaw(data) as BookSoure[]
    },
    setPushReturnSources(returnSoures: Source[]) {
      this.savedSources = returnSoures
    },
    deleteSources(data: Source[]) {
      data.forEach(source => {
        const index = this.bookSources.indexOf(source)
        if (index > -1) this.bookSources.splice(index, 1)
      })
    },
    saveCurrentSource() {
      const source = this.currentSource,
        map = this.sourcesMap
      map.set(getSourceUniqueKey(source), JSON.parse(JSON.stringify(source)))
      this.saveSources(Array.from(map.values()))
    },
    changeCurrentSource(source: Source) {
      this.currentSource = JSON.parse(JSON.stringify(source))
    },
    changeTabName(tabName: string) {
      this.currentTab = tabName
      localStorage.setItem('tabName', tabName)
    },
    changeEditTabSource(source: Source) {
      this.editTabSource = JSON.parse(JSON.stringify(source))
    },
    editHistory(history: Source) {
      let historyObj
      if (localStorage.getItem('history')) {
        historyObj = JSON.parse(localStorage.getItem('history')!)
        historyObj.new.push(history)
        if (historyObj.new.length > 50) {
          historyObj.new.shift()
        }
        if (historyObj.old.length > 50) {
          historyObj.old.shift()
        }
        localStorage.setItem('history', JSON.stringify(historyObj))
      } else {
        const arr = { new: [history], old: [] }
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
