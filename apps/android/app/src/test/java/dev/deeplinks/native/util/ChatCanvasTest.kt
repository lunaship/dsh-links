package dev.deeplinks.native.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatCanvasTest {
    @Test
    fun contentWinsOverLoadingAndError() {
        assertEquals(
            ChatCanvasKind.Content,
            chatCanvasKind(hasMessages = true, initialLoadInFlight = true, hasHistoryError = true, working = true),
        )
    }

    @Test
    fun loadingBeatsErrorAndEmpty() {
        assertEquals(
            ChatCanvasKind.Loading,
            chatCanvasKind(hasMessages = false, initialLoadInFlight = true, hasHistoryError = true, working = false),
        )
    }

    @Test
    fun errorBeatsEmptyAndWorking() {
        assertEquals(
            ChatCanvasKind.Error,
            chatCanvasKind(hasMessages = false, initialLoadInFlight = false, hasHistoryError = true, working = true),
        )
    }

    @Test
    fun workingThenEmpty() {
        assertEquals(
            ChatCanvasKind.Working,
            chatCanvasKind(hasMessages = false, initialLoadInFlight = false, hasHistoryError = false, working = true),
        )
        assertEquals(
            ChatCanvasKind.Empty,
            chatCanvasKind(hasMessages = false, initialLoadInFlight = false, hasHistoryError = false, working = false),
        )
    }

    @Test
    fun loadOlder_loadingBeatsFailed() {
        assertEquals(LoadOlderKind.Loading, loadOlderKind(loading = true, failed = true))
        assertEquals(LoadOlderKind.Failed, loadOlderKind(loading = false, failed = true))
        assertEquals(LoadOlderKind.Idle, loadOlderKind(loading = false, failed = false))
    }

    @Test
    fun sessionList_errorDoesNotLookEmpty() {
        assertEquals(
            SessionListKind.Content,
            sessionListKind(hasSessions = true, initialLoad = true, hasError = true),
        )
        assertEquals(
            SessionListKind.Loading,
            sessionListKind(hasSessions = false, initialLoad = true, hasError = true),
        )
        assertEquals(
            SessionListKind.Error,
            sessionListKind(hasSessions = false, initialLoad = false, hasError = true),
        )
        assertEquals(
            SessionListKind.Empty,
            sessionListKind(hasSessions = false, initialLoad = false, hasError = false),
        )
        assertEquals(
            SessionListKind.Error,
            catalogKind(hasItems = false, initialLoad = false, hasError = true),
        )
        assertEquals(
            SessionListKind.Empty,
            catalogKind(hasItems = false, initialLoad = false, hasError = false),
        )
        assertEquals(true, sessionShowsRefreshBanner(hasSessions = true, hasError = true))
        assertEquals(false, sessionShowsRefreshBanner(hasSessions = true, hasError = false))
        assertEquals(false, sessionShowsRefreshBanner(hasSessions = false, hasError = true))
    }

    @Test
    fun modelPicker_failureWithoutCacheIsErrorNotSpinner() {
        assertEquals(
            SessionListKind.Error,
            catalogKind(hasItems = false, initialLoad = false, hasError = true),
        )
        assertEquals(
            SessionListKind.Loading,
            catalogKind(hasItems = false, initialLoad = true, hasError = false),
        )
        assertEquals(
            SessionListKind.Content,
            catalogKind(hasItems = true, initialLoad = false, hasError = true),
        )
    }

    @Test
    fun renameDialogStaysOpenOnFailure() {
        assertEquals(RenameDialogKind.Saving, renameDialogKind(saving = true, error = "rename failed"))
        assertEquals(RenameDialogKind.Failed, renameDialogKind(saving = false, error = "rename failed"))
        assertEquals(RenameDialogKind.Idle, renameDialogKind(saving = false, error = null))
        assertEquals(RenameDialogKind.Idle, renameDialogKind(saving = false, error = ""))
        assertEquals(true, localHideAfterRemote(accepted = true))
        assertEquals(false, localHideAfterRemote(accepted = false))
        assertEquals(true, forkAccepted("s2"))
        assertEquals(false, forkAccepted(null))
        assertEquals(false, forkAccepted(""))
        assertEquals(false, forkAccepted("  "))
    }
}
