package dev.deeplinks.native.util

/**
 * Android 13+ 系统自己会弹出剪贴板确认。App 再 toast「已复制」是叠 chrome。
 */
fun copiedNeedsAppToast(sdkInt: Int): Boolean = sdkInt < 33
