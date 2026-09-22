/**
 * DSH 的 `session*.jsonl.zstd` 是「按追加逐帧写」的 zstd 容器：会话头一帧，
 * 之后每次落盘再追加一帧。Node 的 `zstdDecompressSync` / `createZstdDecompress`
 * **只解第一帧**（后续帧既不报错也不出现在输出里），单次调用只能拿到会话头。
 *
 * 这里逐帧解压并累计输出，覆盖整个文件。帧边界取自解码器自身消费的压缩字节数
 * （`ZstdDecompress#bytesWritten`），不需要解析 zstd block 结构：
 * 把剩余缓冲整段喂进一个解码器，它解完第一帧就 end，`bytesWritten` 即该帧长度。
 */
import { createZstdDecompress } from "node:zlib"

/** 解压单帧：返回该帧输出、消费的压缩字节数与（若有）错误。 */
function decodeFirstFrame(buf) {
  return new Promise((resolve) => {
    const chunks = []
    let settled = false
    let failure = null
    const stream = createZstdDecompress()
    const finish = () => {
      if (settled) return
      settled = true
      resolve({
        data: Buffer.concat(chunks),
        consumed: Number.isSafeInteger(stream.bytesWritten) ? stream.bytesWritten : 0,
        error: failure,
      })
    }
    stream.on("data", (chunk) => { chunks.push(chunk) })
    stream.on("error", (error) => { failure = error; finish() })
    stream.on("end", finish)
    stream.end(buf)
  })
}

/**
 * 解压全部 zstd 帧并拼接。
 *
 * - 无法继续推进（非 zstd 尾随数据、损坏帧、`bytesWritten` 不可用）时返回
 *   已经解出的前缀，而不是丢掉整段——会话日志宁缺尾不丢头。
 * - 累计输出超过 `maxOutputBytes` 抛错（防压缩炸弹），与单次调用
 *   `zstdDecompressSync(maxOutputLength)` 的语义一致。
 *
 * @param {Buffer} buf 完整的多帧 zstd 缓冲
 * @param {{ maxOutputBytes?: number }} [options]
 * @returns {Promise<Buffer>}
 */
export async function decompressZstdFrames(buf, options = {}) {
  const maxOutputBytes = Number.isSafeInteger(options.maxOutputBytes) && options.maxOutputBytes > 0
    ? options.maxOutputBytes
    : Number.MAX_SAFE_INTEGER
  if (!Buffer.isBuffer(buf) || buf.length === 0) return Buffer.alloc(0)
  const parts = []
  let offset = 0
  let produced = 0
  while (offset < buf.length) {
    const frame = await decodeFirstFrame(buf.subarray(offset))
    if (frame.data.length) {
      produced += frame.data.length
      if (produced > maxOutputBytes) {
        throw new Error(`zstd output exceeds ${maxOutputBytes} bytes`)
      }
      parts.push(frame.data)
    }
    // 损坏帧/半写入帧：留下已解出的部分，不再往下解（后面的字节已不可信）
    if (frame.error || frame.consumed <= 0) break
    offset += frame.consumed
  }
  return Buffer.concat(parts)
}
