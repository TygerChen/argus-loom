import { describe, expect, it } from 'vitest'
import { formatSize, formatTime } from './format'

describe('formatTime', () => {
  it('renders placeholder for empty values', () => {
    expect(formatTime(null)).toBe('-')
    expect(formatTime(undefined)).toBe('-')
  })

  it('formats a valid timestamp', () => {
    const result = formatTime('2026-09-17T08:30:00')
    expect(result).toContain('2026')
  })
})

describe('formatSize', () => {
  it('formats bytes', () => {
    expect(formatSize()).toBe('0 B')
    expect(formatSize(0)).toBe('0 B')
    expect(formatSize(512)).toBe('512.0 B')
    expect(formatSize(1024)).toBe('1.0 KB')
    expect(formatSize(1024 * 1024)).toBe('1.0 MB')
  })
})
