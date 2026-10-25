import { ref } from 'vue'
import {
  checkDocumentDuplicate,
  completeDocumentUpload,
  initDocumentUpload,
  uploadDocumentChunk,
} from '@/api/document'

export type UploadStage = '' | 'hashing' | 'init' | 'uploading' | 'completing'

/**
 * 前端分片默认 5MB（01 篇）：后端上限是 10MB，留一倍余量——
 * 后端限制收紧时前端不会被全线拒收。
 */
const DEFAULT_CHUNK_SIZE = 5 * 1024 * 1024
/** 同哈希文件的默认处理策略：秒传复用 */
export type DuplicatePolicy = 'REUSE' | 'CREATE_NEW'

function toHex(buffer: ArrayBuffer): string {
  return Array.from(new Uint8Array(buffer))
    .map((byte) => byte.toString(16).padStart(2, '0'))
    .join('')
}

async function sha256Hex(data: Blob): Promise<string> {
  /**
   * 浏览器原生密码学工具算 SHA-256（WebCrypto）。
   * 注意 data.arrayBuffer() 会把整块读进内存——文件级指纹对 200MB 文件
   * 也是一次读入，浏览器对 Blob 有虚拟内存优化所以可行；
   * 分片级只有 5MB 更无压力。返回 64 位十六进制字符串。
   */
  const digest = await crypto.subtle.digest('SHA-256', await data.arrayBuffer())
  return toHex(digest)
}

export function useChunkedUpload() {
  /**
   * 分片上传组合式函数 —— 01 篇"搬家三步曲"的完整前端实现。
   *
   * <p><b>四个阶段（UploadStage）驱动 UI</b>：hashing（算指纹，大文件有进度）→
   * init（可能直接秒传）→ uploading（逐片传，跳过已收片）→ completing（合并）。
   * 每个阶段都有独立的进度信号（hashingProgress / chunkProgress / progress），
   * 用户在每一步都"看得见在动"。</p>
   *
   * <p><b>可取消（AbortController）</b>：贯穿全部请求的 abort 信号，
   * 用户点取消立刻中断当前网络请求——上传大文件不给取消按钮是反人类。</p>
   */
  const isUploading = ref(false)
  const progress = ref(0)
  const error = ref('')
  const stage = ref<UploadStage>('')
  const hashingProgress = ref(0)
  const chunkProgress = ref({ uploaded: 0, total: 0 })
  let abortController: AbortController | null = null

  function reset() {
    abortController?.abort()
    abortController = null
    isUploading.value = false
    progress.value = 0
    error.value = ''
    stage.value = ''
    hashingProgress.value = 0
    chunkProgress.value = { uploaded: 0, total: 0 }
  }

  function setProgress(p: number) {
    progress.value = Math.min(100, Math.max(0, p))
  }

  function setError(msg: string) {
    error.value = msg
    isUploading.value = false
    stage.value = ''
  }

  function cancel() {
    abortController?.abort()
    isUploading.value = false
    stage.value = ''
    setProgress(0)
  }

  async function uploadFile(file: File, groupId: number, duplicatePolicy?: DuplicatePolicy): Promise<void> {
    /**
     * 主流程。三个容易忽略的细节：
     * 1. init 返回 uploadedChunks —— 后端告诉你"哪些片已经有了"（断点续传），
     *    循环里 alreadyUploaded.has(index) 直接跳过，绝不重传；
     * 2. 每个分片也算自己的 SHA-256 一起上传 —— 后端落盘前校验，
     *    防止传输中途损坏的片段混进合并（传输完整性校验）；
     * 3. 进度上限 92% —— 最后合并+触发解析还有变数，把 100% 留给"真正完成"，
     *    避免"进度条骗人"。
     */
    if (isUploading.value) return

    reset()
    abortController = new AbortController()
    isUploading.value = true

    try {
      // 1. 文件指纹（后端用于秒传与重复文件识别）
      stage.value = 'hashing'
      hashingProgress.value = 30
      const fileHash = await sha256Hex(file)
      hashingProgress.value = 100

      // 2. 初始化分片会话（可能直接秒传）
      stage.value = 'init'
      const init = await initDocumentUpload(
        {
          groupId,
          fileName: file.name,
          fileSize: file.size,
          contentType: file.type || 'application/octet-stream',
          fileHash,
          chunkSize: DEFAULT_CHUNK_SIZE,
          chunkCount: Math.max(1, Math.ceil(file.size / DEFAULT_CHUNK_SIZE)),
          duplicatePolicy,
        },
        abortController.signal,
      )

      if (init.instantUpload) {
        setProgress(100)
        return
      }
      if (!init.uploadId) {
        throw new Error('初始化上传失败：未返回 uploadId')
      }

      // 3. 逐个上传缺失分片（断点续传自动跳过已上传分片）
      const chunkSize = init.chunkSize ?? DEFAULT_CHUNK_SIZE
      const totalChunks = init.chunkCount ?? Math.max(1, Math.ceil(file.size / chunkSize))
      const alreadyUploaded = new Set(init.uploadedChunks)
      stage.value = 'uploading'
      chunkProgress.value = { uploaded: alreadyUploaded.size, total: totalChunks }

      for (let index = 0; index < totalChunks; index += 1) {
        if (abortController.signal.aborted) return
        if (alreadyUploaded.has(index)) continue

        const start = index * chunkSize
        const chunk = file.slice(start, Math.min(start + chunkSize, file.size))
        const chunkHash = await sha256Hex(chunk)
        await uploadDocumentChunk(
          {
            uploadId: init.uploadId,
            chunkIndex: index,
            chunkHash,
            chunk,
          },
          () => undefined,
          abortController.signal,
        )
        chunkProgress.value = { uploaded: index + 1, total: totalChunks }
        setProgress(Math.round(((index + 1) / totalChunks) * 92))
      }

      // 4. 合并分片并触发异步 ETL
      stage.value = 'completing'
      await completeDocumentUpload(init.uploadId, abortController.signal)
      setProgress(100)
    } catch (err) {
      if (abortController.signal.aborted) {
        setError('')
        return
      }
      setError(err instanceof Error ? err.message : '分片上传失败')
      throw err
    } finally {
      if (!abortController.signal.aborted) {
        isUploading.value = false
        stage.value = ''
      }
    }
  }

  return {
    isUploading,
    progress,
    error,
    stage,
    hashingProgress,
    chunkProgress,
    uploadFile,
    cancel,
    reset,
    setProgress,
    setError,
  }
}

/**
 * 探测同哈希 READY 文档是否已存在（只查不建）。
 * 返回文件哈希与是否命中；命中时上传对话框应先询问用户复用还是新建版本。
 */
export async function probeDuplicate(
  file: File,
  groupId: number,
): Promise<{ fileHash: string; existing: boolean }> {
  const fileHash = await sha256Hex(file)
  const result = await checkDocumentDuplicate({ groupId, fileHash })
  return { fileHash, existing: result.exists }
}
