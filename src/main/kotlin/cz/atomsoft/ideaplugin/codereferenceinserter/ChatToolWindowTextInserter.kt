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

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorModificationUtil
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.content.Content
import java.awt.Component
import java.awt.Container
import java.util.concurrent.CancellationException
import javax.swing.SwingUtilities

/**
 * Finds and writes into the chat input of IntelliJ's built-in AI Assistant/ACP tool window.
 * It is not a compile-time dependency, so detection is done by walking the already-loaded Swing
 * component tree and matching on class-name prefixes, the same reflective spirit as
 * [TerminalTextInserter].
 */
@Service(Service.Level.PROJECT)
class ChatToolWindowTextInserter(private val project: Project) {

    private val logger = logger<ChatToolWindowTextInserter>()
    var lastLookupSummary: String = "Chat lookup has not run yet."
        private set

    internal fun findTargetInContent(toolWindow: ToolWindow, content: Content): TextInsertTarget? {
        if (!isAiAssistantChat(toolWindow, content)) {
            lastLookupSummary = "toolWindow=${toolWindow.id} is not a recognized chat window."
            return null
        }

        val editor = findWritableEditorDescendant(content.component)
        if (editor == null) {
            lastLookupSummary = "toolWindow=${toolWindow.id} matched AI Assistant chat but no writable input editor was found."
            return null
        }

        lastLookupSummary = "toolWindow=${toolWindow.id} matched AI Assistant chat input."
        return TextInsertTarget(
            description = "AI Assistant chat input (${toolWindow.id})",
            toolWindow = toolWindow,
            focus = { editor.contentComponent.requestFocus() },
            write = { text -> insertIntoEditor(editor, text) },
        )
    }

    private fun isAiAssistantChat(toolWindow: ToolWindow, content: Content): Boolean {
        if (containsClassPrefix(content.component, "com.intellij.ml.llm.")) {
            return true
        }

        val idOrTitle = "${toolWindow.id} ${runCatching { toolWindow.stripeTitle }.getOrDefault("")}".lowercase()
        return idOrTitle.contains("ai assistant")
    }

    private fun containsClassPrefix(root: Component, prefix: String): Boolean {
        if (root.javaClass.name.startsWith(prefix)) {
            return true
        }
        if (root is Container) {
            for (child in root.components) {
                if (containsClassPrefix(child, prefix)) {
                    return true
                }
            }
        }
        return false
    }

    private fun findWritableEditorDescendant(root: Component): Editor? {
        return runCatching {
            EditorFactory.getInstance().allEditors.firstOrNull { editor ->
                !editor.isDisposed &&
                    !editor.isViewer &&
                    editor.document.isWritable &&
                    runCatching { SwingUtilities.isDescendingFrom(editor.contentComponent, root) }.getOrDefault(false)
            }
        }.getOrElse {
            logger.warn("Failed to search for chat input editor", it)
            null
        }
    }

    private fun insertIntoEditor(editor: Editor, text: String): Boolean {
        return try {
            WriteCommandAction.runWriteCommandAction(project) {
                EditorModificationUtil.insertStringAtCaret(editor, text, false, true)
            }
            true
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.warn("Failed to insert text into chat input", e)
            false
        }
    }
}
