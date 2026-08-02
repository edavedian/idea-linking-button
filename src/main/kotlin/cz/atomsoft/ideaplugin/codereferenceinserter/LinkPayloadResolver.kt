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

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path

object LinkPayloadResolver {

    private val logger = logger<LinkPayloadResolver>()

    fun resolve(
        project: Project,
        editor: Editor? = null,
        virtualFiles: List<VirtualFile> = emptyList(),
        fallbackFile: VirtualFile? = null,
    ): LinkPayload? {
        if (editor != null) {
            val editorFile = fallbackFile
                ?: FileDocumentManager.getInstance().getFile(editor.document)
                ?: FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
            val entry = editorFile?.let { resolveEditorEntry(project, editor, it) }
            if (entry != null) {
                return LinkPayload(listOf(entry))
            }
        }

        val selectedEntries = virtualFiles
            .ifEmpty { listOfNotNull(fallbackFile) }
            .distinctBy { it.path }
            .mapNotNull { file -> resolveFileEntry(project, file) }

        if (selectedEntries.isNotEmpty()) {
            return LinkPayload(selectedEntries)
        }

        val selectedEditor = FileEditorManager.getInstance(project).selectedTextEditor
        if (selectedEditor != null) {
            val selectedFile = FileDocumentManager.getInstance().getFile(selectedEditor.document)
                ?: FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
            val entry = selectedFile?.let { resolveEditorEntry(project, selectedEditor, it) }
            if (entry != null) {
                return LinkPayload(listOf(entry))
            }
        }

        return null
    }

    fun formatInsertText(payload: LinkPayload): String {
        return payload.entries.joinToString(separator = " ") { entry ->
            quoteIfNeeded(formatEntry(entry))
        } + " "
    }

    fun resolveDisplayPath(projectBasePath: String?, rawPath: String): String {
        val normalizedRaw = normalizeSeparators(rawPath)
        if (projectBasePath.isNullOrBlank()) {
            return normalizedRaw
        }

        return runCatching {
            val base = Path.of(projectBasePath).toAbsolutePath().normalize()
            val target = Path.of(rawPath).toAbsolutePath().normalize()
            val path = if (target.startsWith(base)) {
                base.relativize(target).toString()
            } else {
                target.toString()
            }
            normalizeSeparators(path)
        }.getOrElse {
            logger.warn("Failed to resolve display path for $rawPath", it)
            normalizedRaw
        }
    }

    fun toLineRange(document: Document, startOffset: Int, endOffset: Int): LineRange? {
        if (startOffset < 0 || endOffset < startOffset) {
            return null
        }

        val startLine = runCatching { document.getLineNumber(startOffset) }.getOrElse {
            logger.warn("Failed to resolve start line", it)
            return null
        }

        val adjustedEnd = when {
            endOffset <= startOffset -> startOffset
            endOffset == document.textLength -> endOffset
            else -> endOffset - 1
        }

        val endLine = runCatching { document.getLineNumber(adjustedEnd.coerceAtLeast(startOffset)) }.getOrElse {
            logger.warn("Failed to resolve end line", it)
            startLine
        }

        val start = startLine + 1
        val end = (endLine + 1).takeIf { it > start }
        return LineRange(start, end)
    }

    private fun resolveEditorEntry(project: Project, editor: Editor, file: VirtualFile): LinkPayloadEntry? {
        val path = resolveFilePath(project, file) ?: return null
        val range = if (!file.isDirectory) resolveSelectionLineRange(editor) else null
        return LinkPayloadEntry(path, range)
    }

    private fun resolveFileEntry(project: Project, file: VirtualFile): LinkPayloadEntry? {
        val path = resolveFilePath(project, file) ?: return null
        return LinkPayloadEntry(path, null)
    }

    private fun resolveFilePath(project: Project, file: VirtualFile): String? {
        val rawPath = file.canonicalPath ?: file.presentableUrl ?: file.path
        if (rawPath.isBlank()) {
            return null
        }
        return resolveDisplayPath(project.basePath, rawPath)
    }

    private fun resolveSelectionLineRange(editor: Editor): LineRange? {
        val selectionModel = editor.selectionModel
        if (!selectionModel.hasSelection() || selectionModel.selectedText.isNullOrEmpty()) {
            return null
        }

        return toLineRange(editor.document, selectionModel.selectionStart, selectionModel.selectionEnd)
    }

    private fun formatEntry(entry: LinkPayloadEntry): String {
        return buildString {
            append(entry.path)
            entry.lineRange?.let { range ->
                append(':')
                append(range.start)
                range.end?.takeIf { it != range.start }?.let { end ->
                    append('-')
                    append(end)
                }
            }
        }
    }

    private fun quoteIfNeeded(text: String): String {
        if (text.none { it.isWhitespace() }) {
            return text
        }
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }

    private fun normalizeSeparators(path: String): String = path.replace('\\', '/')
}

data class LinkPayload(val entries: List<LinkPayloadEntry>)

data class LinkPayloadEntry(val path: String, val lineRange: LineRange?)

data class LineRange(val start: Int, val end: Int?)
