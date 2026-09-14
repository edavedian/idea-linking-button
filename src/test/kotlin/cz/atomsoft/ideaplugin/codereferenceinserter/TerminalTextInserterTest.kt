/*
 * Copyright (C) 2026 ATomSoft
 *
 * This file is part of Code Reference Inserter.
 */

package cz.atomsoft.ideaplugin.codereferenceinserter

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.concurrent.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalTextInserterTest : BasePlatformTestCase() {

    fun testSelectedContentMismatchDoesNotUseSingletonFallback() {
        assertEquals(
            TerminalSelectionRoute.NONE,
            terminalSelectionRoute(
                selectedContentAvailable = true,
                selectedContentMatchCount = 0,
                singletonMatchCount = 1,
            ),
        )
    }

    fun testAmbiguousSplitWithoutUniqueFocusIsRejected() {
        assertEquals(
            TerminalSelectionRoute.SELECTED_CONTENT,
            terminalSelectionRoute(
                selectedContentAvailable = true,
                selectedContentMatchCount = 2,
                singletonMatchCount = 1,
            ),
        )
        assertNull(selectFocusedTerminal(listOf("left", "right")) { false })
        assertEquals("right", selectFocusedTerminal(listOf("left", "right")) { it == "right" })
        assertNull(selectFocusedTerminal(listOf("left", "right")) { true })
    }

    fun testSuccessfulWriteRemainsSuccessfulWhenActivationFails() {
        var writtenText: String? = null
        var activationFailures = 0

        val result = executeTerminalInsertion(
            text = "src/Foo.kt ",
            write = { writtenText = it },
            activate = { error("focus unavailable") },
            onActivationFailure = { activationFailures++ },
            onWriteFailure = { error("write must not fail: $it") },
        )

        assertTrue(result)
        assertEquals("src/Foo.kt ", writtenText)
        assertEquals(1, activationFailures)
    }

    fun testProcessCancellationIsPropagatedFromWrite() {
        assertFailsWith<ProcessCanceledException> {
            executeTerminalInsertion(
                text = "text",
                write = { throw ProcessCanceledException() },
                activate = {},
                onActivationFailure = { error("activation must not run") },
                onWriteFailure = { error("cancellation must not be reported as write failure") },
            )
        }
    }

    fun testCancellationExceptionIsPropagatedFromActivation() {
        assertFailsWith<CancellationException> {
            executeTerminalInsertion(
                text = "text",
                write = {},
                activate = { throw CancellationException("cancelled") },
                onActivationFailure = { error("cancellation must not be treated as activation failure") },
                onWriteFailure = { error("write must not fail") },
            )
        }
    }

    fun testUnavailableClassicConnectorIsNotReportedAsWritten() {
        assertFalse(writeTerminalConnector(null, "text"))
    }
}
