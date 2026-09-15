/*
 * Copyright (C) 2026 ATomSoft
 *
 * This file is part of Code Reference Inserter.
 *
 * Code Reference Inserter is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * Code Reference Inserter is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Code Reference Inserter. If not, see <https://www.gnu.org/licenses/>.
 */

package cz.atomsoft.ideaplugin.codereferenceinserter

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import org.jetbrains.plugins.terminal.TerminalToolWindowFactory
import java.util.concurrent.CancellationException

/**
 * Single entry point for sending the formatted code reference text to whichever active surface
 * (chat tool window or terminal) is the most likely intended target.
 *
 * [TerminalTextInserter] resolves and writes to the terminal as a single, self-contained step
 * (no longer exposing a per-tool-window probe), so precedence between it and the chat surfaces
 * is decided coarsely: when the Terminal tool window is the IDE's active tool window, it is
 * tried first and exclusively; otherwise chat candidates are scanned in priority order first,
 * with the terminal's own resolution (including its sole-widget fallback) tried last.
 */
@Service(Service.Level.PROJECT)
class CodeReferenceInsertionDispatcher(private val project: Project) {

    private val logger = logger<CodeReferenceInsertionDispatcher>()
    var lastLookupSummary: String = "Insertion has not run yet."
        private set

    fun insert(text: String): Boolean {
        return try {
            val chat = project.service<ChatToolWindowTextInserter>()
            val terminal = project.service<TerminalTextInserter>()

            val toolWindowManager = ToolWindowManager.getInstance(project)
            val terminalIsActive = toolWindowManager.activeToolWindowId == TerminalToolWindowFactory.TOOL_WINDOW_ID

            if (terminalIsActive) {
                // Terminal is documented as the exclusive target here: a failed write must not
                // fall through to an unrelated visible chat window, so return its result as-is.
                val written = terminal.insertIntoSelectedTerminal(text)
                lastLookupSummary = terminal.lastLookupSummary
                return written
            }

            val candidates = ToolWindowCandidateOrder.current(
                toolWindowManager,
                setOf(TerminalToolWindowFactory.TOOL_WINDOW_ID),
            )

            for (toolWindow in candidates) {
                val content = toolWindow.contentManager.selectedContent ?: continue
                val target = chat.findTargetInContent(toolWindow, content) ?: continue

                if (target.write(text)) {
                    activate(target)
                    lastLookupSummary = "Inserted into ${target.description}."
                    return true
                }
            }

            if (terminal.insertIntoSelectedTerminal(text)) {
                lastLookupSummary = terminal.lastLookupSummary
                return true
            }

            lastLookupSummary = "No chat or terminal target found. chat=${chat.lastLookupSummary} terminal=${terminal.lastLookupSummary}"
            false
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.warn("Failed to dispatch code reference insertion", e)
            lastLookupSummary = "Exception during insertion dispatch: ${e.javaClass.simpleName}: ${e.message}"
            false
        }
    }

    private fun activate(target: TextInsertTarget) {
        try {
            target.toolWindow?.activate({ target.focus() }, true) ?: target.focus()
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.warn("Failed to request focus for insertion target", e)
        }
    }
}
