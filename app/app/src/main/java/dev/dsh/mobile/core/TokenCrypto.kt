package dev.dsh.mobile.core
import dev.dsh.mobile.core.TokenCrypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 主机凭据（配对 token）加密存储 —— Android Keystore AES/GCM。
 *
 * token 是访问 dsh 服务器的唯一凭证，明文放 SharedPreferences 任何拿到备份的人都能读；
 * 这里用不可导出的 Keystore 密钥做 AES-256-GCM 加密（IV 随机、密文带认证标签，
 * 篡改/换机都会解密失败）。密钥首次使用时生成，之后常驻 Keystore。
 */
object TokenCrypto {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "dsh_hosts_key_v1"
    private const val PREFIX = "enc1:"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    fun encrypt(context: Context, plaintext: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(context))
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        return PREFIX +
            Base64.encodeToString(iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    /** 解密失败（密钥丢失/数据损坏）返回 null，调用方按旧数据不存在处理。 */
    fun decrypt(context: Context, blob: String): String? {
        if (!blob.startsWith(PREFIX)) return null
        val body = blob.removePrefix(PREFIX)
        val sep = body.indexOf(':')
        if (sep <= 0) return null
        return try {
            val iv = Base64.decode(body.substring(0, sep), Base64.NO_WRAP)
            val ct = Base64.decode(body.substring(sep + 1), Base64.NO_WRAP)
            if (iv.size != IV_BYTES) return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(context), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: AEADBadTagException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    fun isEncrypted(blob: String): Boolean = blob.startsWith(PREFIX)

    private fun getOrCreateKey(context: Context): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
