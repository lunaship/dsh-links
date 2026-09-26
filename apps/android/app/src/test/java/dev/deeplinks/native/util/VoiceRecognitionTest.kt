package dev.deeplinks.native.util

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceRecognitionTest {
    @Test
    fun noSpeech_mapsTimeoutAndNoMatch() {
        assertEquals(VoiceRecognitionIssue.NO_SPEECH, voiceRecognitionIssue(VOICE_ERROR_SPEECH_TIMEOUT))
        assertEquals(VoiceRecognitionIssue.NO_SPEECH, voiceRecognitionIssue(VOICE_ERROR_NO_MATCH))
    }

    @Test
    fun network_mapsTimeoutServerAndDisconnect() {
        assertEquals(VoiceRecognitionIssue.NETWORK, voiceRecognitionIssue(VOICE_ERROR_NETWORK))
        assertEquals(VoiceRecognitionIssue.NETWORK, voiceRecognitionIssue(VOICE_ERROR_NETWORK_TIMEOUT))
        assertEquals(VoiceRecognitionIssue.NETWORK, voiceRecognitionIssue(VOICE_ERROR_SERVER))
        assertEquals(VoiceRecognitionIssue.NETWORK, voiceRecognitionIssue(VOICE_ERROR_SERVER_DISCONNECTED))
    }

    @Test
    fun busy_mapsBusyAndTooManyRequests() {
        assertEquals(VoiceRecognitionIssue.BUSY, voiceRecognitionIssue(VOICE_ERROR_RECOGNIZER_BUSY))
        assertEquals(VoiceRecognitionIssue.BUSY, voiceRecognitionIssue(VOICE_ERROR_TOO_MANY_REQUESTS))
    }

    @Test
    fun permissionAndRetry() {
        assertEquals(VoiceRecognitionIssue.PERMISSION, voiceRecognitionIssue(VOICE_ERROR_INSUFFICIENT_PERMISSIONS))
        assertEquals(VoiceRecognitionIssue.RETRY, voiceRecognitionIssue(5))
        assertEquals(VoiceRecognitionIssue.RETRY, voiceRecognitionIssue(0))
    }
}
