/**
 * 18640 自签证书：身份是 SHA-256 指纹，不依赖主机名/IP SAN。
 * App 按指纹钉死；换 Wi-Fi 或局域网 IP 变化不影响。
 */
import { chmodSync, existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from "node:fs"
import { join } from "node:path"
import { X509Certificate } from "node:crypto"
import selfsigned from "selfsigned"

export function certFingerprintSha256(pem) {
  const cert = new X509Certificate(pem)
  return String(cert.fingerprint256).replace(/:/g, "").toLowerCase()
}

export async function loadOrCreateTls(stateDir) {
  mkdirSync(stateDir, { recursive: true, mode: 0o700 })
  try { chmodSync(stateDir, 0o700) } catch {}
  const file = join(stateDir, "tls.json")
  if (existsSync(file)) {
    try {
      const data = JSON.parse(readFileSync(file, "utf8"))
      if (data?.key && data?.cert) {
        const fingerprint = data.fingerprint || certFingerprintSha256(data.cert)
        return { key: data.key, cert: data.cert, fingerprint }
      }
    } catch {
      // 损坏则重新生成
    }
  }
  const notAfter = new Date()
  notAfter.setFullYear(notAfter.getFullYear() + 10)
  const pems = await selfsigned.generate(
    [{ name: "commonName", value: "dsh-links" }],
    { keySize: 2048, algorithm: "sha256", notAfterDate: notAfter },
  )
  const fingerprint = certFingerprintSha256(pems.cert)
  const data = { key: pems.private, cert: pems.cert, fingerprint }
  const tmp = `${file}.${process.pid}.tmp`
  writeFileSync(tmp, JSON.stringify(data, null, 2), { mode: 0o600 })
  renameSync(tmp, file)
  try { chmodSync(file, 0o600) } catch {}
  return data
}
