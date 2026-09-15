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
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.content.Content
import java.awt.Component
import java.awt.Container
import javax.swing.SwingUtilities

/**
 * Finds and writes into the chat input of known non-terminal chat tool windows (IntelliJ's
 * built-in AI Assistant/ACP window, GitHub Copilot's dedicated chat window). Neither plugin is
 * a compile-time dependency, so detection is done by walking the already-loaded Swing component
 * tree and matching on class-name prefixes, the same reflective spirit as [TerminalTextInserter].
 */
@Service(Service.Level.PROJECT)
class ChatToolWindowTextInserter(private val project: Project) {

    private val logger = logger<ChatToolWindowTextInserter>()
    var lastLookupSummary: String = "Chat lookup has not run yet."
        private set

    internal fun findTargetInContent(toolWindow: ToolWindow, content: Content): TextInsertTarget? {
        // TEMPORARY diagnostics (to be removed once Copilot detection is confirmed working):
        // dumps the content's component tree so we can see why classification/editor lookup fails.
        logger.warn(
            "CRI-DEBUG scan toolWindow=${toolWindow.id} content=${content.displayName} " +
                "rootClass=${content.component?.javaClass?.name}\n" +
                dumpComponentTree(content.component),
        )

        val kind = classify(toolWindow, content)
        if (kind == null) {
            lastLookupSummary = "toolWindow=${toolWindow.id} is not a recognized chat window."
            return null
        }

        logger.warn(
            "CRI-DEBUG classified toolWindow=${toolWindow.id} as $kind, searching editors " +
                "(allEditors=${EditorFactory.getInstance().allEditors.size}):\n" +
                describeCandidateEditors(content.component),
        )

        val editor = findWritableEditorDescendant(content.component)
        if (editor == null) {
            lastLookupSummary = "toolWindow=${toolWindow.id} matched $kind but no writable chat input editor was found."
            return null
        }

        lastLookupSummary = "toolWindow=${toolWindow.id} matched $kind chat input."
        return TextInsertTarget(
            description = "$kind chat input (${toolWindow.id})",
            toolWindow = toolWindow,
            focus = { editor.contentComponent.requestFocus() },
            write = { text -> insertIntoEditor(editor, text) },
        )
    }

    private fun dumpComponentTree(root: Component?, depth: Int = 0, maxDepth: Int = 6): String {
        if (root == null || depth > maxDepth) return ""
        val line = "${"  ".repeat(depth)}${root.javaClass.name}\n"
        return if (root is Container) {
            line + root.components.joinToString("") { dumpComponentTree(it, depth + 1, maxDepth) }
        } else {
            line
        }
    }

    private fun describeCandidateEditors(root: Component?): String {
        if (root == null) return "  (no root component)\n"
        return EditorFactory.getInstance().allEditors.joinToString("") { editor ->
            val descending = runCatching { SwingUtilities.isDescendingFrom(editor.contentComponent, root) }.getOrDefault(false)
            "  editor=${editor.javaClass.name} disposed=${editor.isDisposed} viewer=${editor.isViewer} " +
                "writable=${editor.document.isWritable} descendsFromRoot=$descending\n"
        }
    }

    private fun classify(toolWindow: ToolWindow, content: Content): ChatTargetKind? {
        val root = content.component
        if (containsClassPrefix(root, "com.intellij.ml.llm.")) {
            return ChatTargetKind.AI_ASSISTANT
        }
        if (containsClassPrefix(root, "com.github.copilot.")) {
            return ChatTargetKind.COPILOT
        }

        val idOrTitle = "${toolWindow.id} ${runCatching { toolWindow.stripeTitle }.getOrDefault("")}".lowercase()
        if (idOrTitle.contains("ai assistant")) {
            return ChatTargetKind.AI_ASSISTANT
        }
        if (toolWindow.id == "GitHub Copilot Chat") {
            return ChatTargetKind.COPILOT
        }

        return null
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
        return runCatching {
            WriteCommandAction.runWriteCommandAction(project) {
                EditorModificationUtil.insertStringAtCaret(editor, text, false, true)
            }
            true
        }.getOrElse {
            logger.warn("Failed to insert text into chat input", it)
            false
        }
    }

    private enum class ChatTargetKind { AI_ASSISTANT, COPILOT }
}
