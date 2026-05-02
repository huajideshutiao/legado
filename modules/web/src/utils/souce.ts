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

export const normalizeSource = (source: any) => {
  for (const key in source) {
    const value = source[key]
    if (
      value === '' ||
      value === null ||
      (typeof value === 'string' && !value.trim())
    ) {
      delete source[key]
    } else if (value instanceof Object) {
      normalizeSource(value)
    }
  }
}

export const emptyBookSource = {
  ruleSearch: {},
  ruleBookInfo: {},
  ruleToc: {},
  ruleContent: {},
  ruleExplore: {},
} as BookSoure
