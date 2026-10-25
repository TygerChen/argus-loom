import { describe, expect, it } from 'vitest'
import { extractApiError } from './http'

describe('extractApiError', () => {
  it('extracts the backend business message from axios errors', () => {
    const error = new Error('Request failed')
    Object.assign(error, {
      isAxiosError: true,
      response: { data: { message: '知识库不存在', success: false } },
    })
    expect(extractApiError(error, '兜底')).toBe('知识库不存在')
  })

  it('falls back to the Error message', () => {
    expect(extractApiError(new Error('network down'), '兜底')).toBe('network down')
  })

  it('falls back when the error carries no readable message', () => {
    expect(extractApiError({}, '兜底')).toBe('兜底')
  })
})
