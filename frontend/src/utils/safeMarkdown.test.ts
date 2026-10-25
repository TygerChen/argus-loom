import { describe, expect, it } from 'vitest'
import { renderSafeMarkdown } from './safeMarkdown'

describe('renderSafeMarkdown', () => {
  it('renders normal markdown', () => {
    const html = renderSafeMarkdown('# 标题\n\n**加粗** and `code`')
    expect(html).toContain('<h1>')
    expect(html).toContain('<strong>')
  })

  it('renders fenced code blocks', () => {
    const html = renderSafeMarkdown('```js\nconst x = 1\n```')
    expect(html).toContain('const x = 1')
    expect(html).toContain('<pre>')
  })

  it('renders tables', () => {
    const html = renderSafeMarkdown('| a | b |\n| - | - |\n| 1 | 2 |')
    expect(html).toContain('<table>')
  })

  it('removes script tags', () => {
    const html = renderSafeMarkdown('hello <script>alert(1)</script>')
    expect(html).not.toContain('<script')
    expect(html).not.toContain('alert(1)')
  })

  it('removes event handler attributes', () => {
    const html = renderSafeMarkdown('<img src=x onerror=alert(1)>')
    expect(html).not.toContain('onerror')
  })

  it('removes javascript: links', () => {
    const html = renderSafeMarkdown('[click](javascript:alert(1))')
    expect(html).not.toContain('javascript:')
  })

  it('removes inline styles and iframes', () => {
    const html = renderSafeMarkdown('<iframe src="https://evil.example"></iframe><p style="color:red">t</p>')
    expect(html).not.toContain('<iframe')
    expect(html).not.toContain('style=')
  })

  it('returns empty string for empty input', () => {
    expect(renderSafeMarkdown('')).toBe('')
    expect(renderSafeMarkdown('   ')).toBe('')
  })
})
