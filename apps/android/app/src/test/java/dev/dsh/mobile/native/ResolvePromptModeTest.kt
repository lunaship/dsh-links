package dev.dsh.mobile.native

import org.junit.Assert.assertEquals
import org.junit.Test

class ResolvePromptModeTest {
    @Test
    fun idleAlwaysQueues() {
        assertEquals("queue", resolvePromptMode(running = false, busyEnter = "send"))
        assertEquals("queue", resolvePromptMode(running = false, busyEnter = "steer"))
        assertEquals("queue", resolvePromptMode(running = false, busyEnter = "queue"))
    }

    @Test
    fun runningUsesBusyEnterWhenSupported() {
        assertEquals("send", resolvePromptMode(running = true, busyEnter = "send"))
        assertEquals("steer", resolvePromptMode(running = true, busyEnter = "steer"))
    }

    @Test
    fun runningFallsBackToQueueForUnknownBusyEnter() {
        assertEquals("queue", resolvePromptMode(running = true, busyEnter = "queue"))
        assertEquals("queue", resolvePromptMode(running = true, busyEnter = "newline"))
        assertEquals("queue", resolvePromptMode(running = true, busyEnter = "unknown"))
    }

    @Test
    fun canonicalBusyEnterNormalizesLegacyValues() {
        assertEquals("queue", canonicalBusyEnter("newline"))
        assertEquals("queue", canonicalBusyEnter("queue"))
        assertEquals("send", canonicalBusyEnter("send"))
        assertEquals("插话发送", busyEnterLabel("send"))
        assertEquals("引导发送", busyEnterLabel("steer"))
    }
}
