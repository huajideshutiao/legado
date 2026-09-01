import type { BookSource, RawSource, Source } from '../source'
import { isNullOrBlank } from './utils'

export const isInvaildSource: (source: Source | RawSource) => boolean = source => {
  return (
    !isNullOrBlank((source as BookSource).bookSourceName) &&
    !isNullOrBlank((source as BookSource).bookSourceUrl) &&
    !isNullOrBlank((source as BookSource).bookSourceType)
  )
}

export const getSourceUniqueKey = (source: Source | RawSource) =>
  (source as BookSource).bookSourceUrl
export const getSourceName = (source: Source | RawSource) =>
  (source as BookSource).bookSourceName

export const isSourceMatches: (source: Source | RawSource, searchKey: string) => boolean = (
  source,
  searchKey,
) => {
  const s = source as BookSource
  return (
    (s.bookSourceName?.includes(searchKey) ||
      s.bookSourceUrl?.includes(searchKey) ||
      s.bookSourceGroup?.includes(searchKey) ||
      s.bookSourceComment?.includes(searchKey)) ??
    false
  )
}

export const convertSourcesToMap = (sources: Source[]): Map<string, Source> => {
  const map = new Map<string, Source>()
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

export const ensureSourceRules = (
  source: RawSource | Source | Record<string, unknown>,
): Source => {
  if (!source || typeof source !== 'object') return source as Source
  const s = { ...source } as Record<string, unknown>
  for (const ns of RULE_NAMESPACES) {
    const val = s[ns]
    if (typeof val === 'string') {
      const trimmed = val.trim()
      if (trimmed.startsWith('{') || trimmed.startsWith('[')) {
        try {
          const parsed = JSON.parse(trimmed)
          s[ns] = parsed && typeof parsed === 'object' ? parsed : {}
        } catch {
          s[ns] = {}
        }
      } else {
        s[ns] = {}
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

export const emptyBookSource: BookSource = {
  bookSourceName: '',
  bookSourceUrl: '',
  bookSourceType: 0,
  customOrder: 0,
  enabled: true,
  enabledExplore: true,
  lastUpdateTime: 0,
  respondTime: 180000,
  weight: 0,
  ruleSearch: {},
  ruleBookInfo: {},
  ruleToc: {},
  ruleContent: {},
  ruleExplore: {},
  ruleReview: {},
}

