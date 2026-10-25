import { marked } from 'marked'
import DOMPurify from 'dompurify'

/**
 * 全站唯一的 Markdown → HTML 渲染入口。
 *
 * 模型回复、上传文档预览都属于不可信输入：marked 解析出的 HTML 可能携带
 * <script>、事件属性（onclick 等）、javascript: 链接或危险协议（data:/vbscript:）。
 * 所有 v-html 必须经由本函数输出，禁止在组件内直接调用 marked.parse。
 */
marked.setOptions({ gfm: true, breaks: true })

const FORBID_TAGS = ['script', 'style', 'iframe', 'object', 'embed', 'form', 'base']

export function renderSafeMarkdown(raw: string): string {
  if (!raw || !raw.trim()) return ''
  const html = marked.parse(raw) as string
  // happy-dom/jsdom 等测试环境下 DOMPurify 拿不到全局 window 时会静默跳过净化，
  // 显式绑定当前 window 保证浏览器与测试环境行为一致。
  const purifier = DOMPurify(window)
  return purifier.sanitize(html, {
    FORBID_TAGS,
    // a/img 的 href/src 由 DOMPurify 默认协议白名单约束（http/https/mailto 等），
    // javascript:/data:/vbscript: 一律被移除。
    FORBID_ATTR: ['style'],
  })
}
