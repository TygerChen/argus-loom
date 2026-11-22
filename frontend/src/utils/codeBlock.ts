/**
 * Markdown 代码块增强 —— 给 renderSafeMarkdown 产出的 <pre><code> 补上
 * "语言标签 + 一键复制"顶栏（LobeChat/ChatGPT 级对话体验的标配）。
 *
 * 设计要点：
 * - 只处理没有 data-code-enhanced 标记的 pre，流式增量渲染下可反复调用不重复包壳；
 * - 元素全部程序化创建（textContent 赋值），不从 markdown 注入任何 HTML，无 XSS 面；
 * - 语言从 <code> 的 language-* class 读取（marked gfm 围栏会带上），读不到显示 text；
 * - 样式挂在全局 main.css 的 .code-shell 上——动态创建的元素没有 scoped data-v 属性，
 *   写在组件 scoped style 里永远不会命中。
 */

const LANG_CLASS_RE = /language-([\w+#-]+)/

/**
 * 围栏规整：LLM 常把 ``` 直接跟在正文后面（"方法：```java"），
 * 此时不处于行首，marked 会把整段当成"行内代码"而不是围栏代码块——
 * 表现为多行代码挤进一个 <code>、没有 <pre>、复制/语言标签全部失效。
 * 在渲染前把所有非行首的 ``` 前面补一个换行即可（开栏与闭栏都受益）。
 * 代价：正文中恰好出现三个连续反引号的行内用法会被拆行——对话场景可忽略。
 */
export function normalizeFences(markdown: string): string {
  return markdown.replace(/([^\n])```/g, '$1\n```')
}

export function enhanceCodeBlocks(container: HTMLElement | null | undefined): void {
  if (!container) return
  const pres = container.querySelectorAll('pre:not([data-code-enhanced])')
  for (const pre of Array.from(pres)) {
    pre.setAttribute('data-code-enhanced', 'true')

    const code = pre.querySelector('code')
    const lang = code?.className.match(LANG_CLASS_RE)?.[1] ?? ''

    const bar = document.createElement('div')
    bar.className = 'code-shell__bar'

    const langLabel = document.createElement('span')
    langLabel.className = 'code-shell__lang'
    langLabel.textContent = lang || 'text'
    bar.appendChild(langLabel)

    const copyBtn = document.createElement('button')
    copyBtn.type = 'button'
    copyBtn.className = 'code-shell__copy'
    copyBtn.textContent = '复制'
    copyBtn.addEventListener('click', () => {
      const text = code?.textContent ?? pre.textContent ?? ''
      const done = (label: string) => {
        copyBtn.textContent = label
        window.setTimeout(() => {
          copyBtn.textContent = '复制'
        }, 1600)
      }
      if (navigator.clipboard?.writeText) {
        navigator.clipboard
          .writeText(text)
          .then(() => done('已复制 ✓'))
          .catch(() => fallbackCopy(text, () => done('已复制 ✓'), () => done('复制失败')))
      } else {
        fallbackCopy(text, () => done('已复制 ✓'), () => done('复制失败'))
      }
    })
    bar.appendChild(copyBtn)

    // 包壳：wrapper 同时容纳顶栏与原 pre，保持 pre 自身结构不动
    const shell = document.createElement('div')
    shell.className = 'code-shell'
    pre.parentNode?.insertBefore(shell, pre)
    shell.appendChild(bar)
    shell.appendChild(pre)
  }
}

/** 剪贴板 API 不可用（http 环境等）时的 execCommand 兜底 */
function fallbackCopy(text: string, onSuccess: () => void, onError: () => void): void {
  try {
    const ta = document.createElement('textarea')
    ta.value = text
    ta.style.position = 'fixed'
    ta.style.opacity = '0'
    document.body.appendChild(ta)
    ta.select()
    const ok = document.execCommand('copy')
    document.body.removeChild(ta)
    if (ok) onSuccess()
    else onError()
  } catch {
    onError()
  }
}
