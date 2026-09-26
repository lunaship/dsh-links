package dev.deeplinks.native.util

/**
 * SpeechRecognizer 错误码（与 android.speech.SpeechRecognizer 数值一致，避免 JVM 单测依赖 Android）。
 * OpenClaw Android：不可用 / 无语音 / 网络 / 忙 要说出来，不能静默结束倾听。
 */
enum class VoiceRecognitionIssue {
    NO_SPEECH,
    NETWORK,
    BUSY,
    PERMISSION,
    RETRY,
}

const val VOICE_ERROR_NETWORK_TIMEOUT = 1
const val VOICE_ERROR_NETWORK = 2
const val VOICE_ERROR_SERVER = 4
const val VOICE_ERROR_SPEECH_TIMEOUT = 6
const val VOICE_ERROR_NO_MATCH = 7
const val VOICE_ERROR_RECOGNIZER_BUSY = 8
const val VOICE_ERROR_INSUFFICIENT_PERMISSIONS = 9
const val VOICE_ERROR_TOO_MANY_REQUESTS = 10
const val VOICE_ERROR_SERVER_DISCONNECTED = 11

fun voiceRecognitionIssue(errorCode: Int): VoiceRecognitionIssue = when (errorCode) {
    VOICE_ERROR_SPEECH_TIMEOUT, VOICE_ERROR_NO_MATCH -> VoiceRecognitionIssue.NO_SPEECH
    VOICE_ERROR_NETWORK_TIMEOUT, VOICE_ERROR_NETWORK, VOICE_ERROR_SERVER, VOICE_ERROR_SERVER_DISCONNECTED ->
        VoiceRecognitionIssue.NETWORK
    VOICE_ERROR_RECOGNIZER_BUSY, VOICE_ERROR_TOO_MANY_REQUESTS -> VoiceRecognitionIssue.BUSY
    VOICE_ERROR_INSUFFICIENT_PERMISSIONS -> VoiceRecognitionIssue.PERMISSION
    else -> VoiceRecognitionIssue.RETRY
}
