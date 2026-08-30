import type { BookSoure, Source } from '../source'
import { isNullOrBlank } from './utils'

export const isInvaildSource: (source: Source) => boolean = source => {
  return (
    !isNullOrBlank((source as BookSoure).bookSourceName) &&
    !isNullOrBlank((source as BookSoure).bookSourceUrl) &&
    !isNullOrBlank((source as BookSoure).bookSourceType)
  )
}

export const getSourceUniqueKey = (source: Source) =>
  (source as BookSoure).bookSourceUrl
export const getSourceName = (source: Source) =>
  (source as BookSoure).bookSourceName

export const isSourceMatches: (source: Source, searchKey: string) => boolean = (
  source,
  searchKey,
) => {
  const s = source as BookSoure
  return (
    (s.bookSourceName.includes(searchKey) ||
      s.bookSourceUrl.includes(searchKey) ||
      s.bookSourceGroup?.includes(searchKey) ||
      s.bookSourceComment?.includes(searchKey)) ??
    false
  )
}

export const convertSourcesToMap = (sources: Source[]): Map<string, Source> => {
  const map = new Map()
  sources.forEach(source => map.set(getSourceUniqueKey(source), source))
  return map
}

export const RULE_NAMESPACES = [
  'ruleSearch',
  'ruleExplore',
  'ruleBookInfo',
  'ruleToc',
  'ruleContent',
  'ruleReview',
] as const

export const ensureSourceRules = (source: Source): Source => {
  if (!source || typeof source !== 'object') return source
  const s = { ...source } as Record<string, unknown>
  for (const ns of RULE_NAMESPACES) {
    const val = s[ns]
    if (typeof val === 'string') {
      const trimmed = val.trim()
      if (trimmed.startsWith('{') || trimmed.startsWith('[')) {
        try {
          s[ns] = JSON.parse(trimmed)
        } catch {
          s[ns] = {}
        }
      } else if (trimmed === '') {
        s[ns] = {}
      } else {
        // 如果是纯字符串格式
        s[ns] = val
      }
    } else if (!val || typeof val !== 'object') {
      s[ns] = {}
    }
  }
  return s as Source
}

export const normalizeSource = (source: Record<string, unknown>) => {
  for (const key in source) {
    const value = source[key]
    if (
      value === '' ||
      value === null ||
      (typeof value === 'string' && !value.trim())
    ) {
      delete source[key]
    } else if (value instanceof Object) {
      normalizeSource(value as Record<string, unknown>)
    }
  }
}

export const emptyBookSource = {
  ruleSearch: {},
  ruleBookInfo: {},
  ruleToc: {},
  ruleContent: {},
  ruleExplore: {},
  ruleReview: {},
} as BookSoure

