package dev.deeplinks.native

import android.content.Context
import android.content.Intent

/** 系统分享纯文本（消息长按）。图片卡片仍走 [ShareCardRenderer]。 */
object ShareIntents {
    fun shareText(context: Context, text: String, chooserTitle: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(send, chooserTitle))
    }
}
