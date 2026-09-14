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
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** Resolves IDE action context into safe, project-aware code reference text. */
object LinkPayloadResolver {

    private val logger = logger<LinkPayloadResolver>()

    /**
     * Resolves the highest-priority usable context. Explicit Project View or
     * editor-tab file selections win over an editor left on the event; a
     * singleton file derived from that editor keeps its selection. A fallback
     * file receives a selection only when it belongs to the editor document.
     */
    fun resolve(
        project: Project,
        editor: Editor? = null,
        virtualFiles: List<VirtualFile> = emptyList(),
        fallbackFile: VirtualFile? = null,
        explicitFileSelection: Boolean = false,
    ): LinkPayload? {
        val editorFile = editor?.let { documentFile(it) }
        val derivedSingleEditorFile = virtualFiles.size == 1 &&
            editorFile != null &&
            sameFile(editorFile, virtualFiles.single())
        if (virtualFiles.isNotEmpty() && (!derivedSingleEditorFile || explicitFileSelection)) {
            return resolveFileEntries(project, virtualFiles)
        }

        if (fallbackFile != null) {
            val entry = if (!explicitFileSelection &&
                editor != null &&
                editorFile != null &&
                sameFile(editorFile, fallbackFile)
            ) {
                resolveEditorEntry(project, editor, fallbackFile)
            } else {
                resolveFileEntry(project, fallbackFile)
            }
            return entry?.let { LinkPayload(listOf(it)) }
        }

        if (editor != null) {
            val entry = editorFile?.let { resolveEditorEntry(project, editor, it) }
            if (entry != null) {
                return LinkPayload(listOf(entry))
            }
        }

        val selectedEditor = FileEditorManager.getInstance(project).selectedTextEditor
        if (selectedEditor != null) {
            val selectedFile = documentFile(selectedEditor)
            val entry = selectedFile?.let { resolveEditorEntry(project, selectedEditor, it) }
            if (entry != null) {
                return LinkPayload(listOf(entry))
            }
        }

        return null
    }

    private fun resolveFileEntries(project: Project, files: List<VirtualFile>): LinkPayload? {
        val entries = files
            .distinctBy { it.url }
            .mapNotNull { file -> resolveFileEntry(project, file) }
        return entries.takeIf { it.isNotEmpty() }?.let(::LinkPayload)
    }

    /** Formats references as deterministic whitespace-safe tokens, without shell detection. */
    fun formatInsertText(payload: LinkPayload): String {
        return payload.entries.joinToString(separator = " ") { entry ->
            quoteIfNeeded(formatEntry(entry))
        } + " "
    }

    /**
     * Returns an absolute normalized path for the project root, project-relative
     * paths for descendants, and preserves non-local URI references.
     */
    fun resolveDisplayPath(projectBasePath: String?, rawPath: String): String {
        val normalizedRaw = normalizeSeparators(rawPath)
        if (projectBasePath.isNullOrBlank() || rawPath.contains("://") || projectBasePath.contains("://")) {
            return normalizedRaw
        }

        return try {
            val base = Path.of(projectBasePath).toAbsolutePath().normalize()
            val target = Path.of(rawPath).toAbsolutePath().normalize()
            val path = when {
                target == base -> target.toString()
                target.startsWith(base) -> base.relativize(target).toString()
                else -> target.toString()
            }
            normalizeSeparators(path)
        } catch (exception: InvalidPathException) {
            logger.warn("Failed to resolve display path for $rawPath", exception)
            normalizedRaw
        } catch (exception: SecurityException) {
            logger.warn("Failed to resolve display path for $rawPath", exception)
            normalizedRaw
        }
    }

    /**
     * Converts a valid half-open document selection to one-based inclusive
     * lines. The exclusive end is decremented for every non-empty selection.
     */
    fun toLineRange(document: Document, startOffset: Int, endOffset: Int): LineRange? {
        if (startOffset !in 0..endOffset || endOffset > document.textLength) {
            return null
        }

        val startLine = document.getLineNumber(startOffset)

        val adjustedEnd = if (endOffset > startOffset) endOffset - 1 else startOffset

        val endLine = document.getLineNumber(adjustedEnd)

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
        val rawPath = file.canonicalPath ?: file.presentableUrl
        if (rawPath.isBlank() || containsControlCharacter(rawPath)) {
            return null
        }
        val displayPath = if (file.isInLocalFileSystem) {
            resolveDisplayPath(project.basePath, rawPath)
        } else {
            normalizeSeparators(rawPath)
        }
        return displayPath.takeIf { it.isNotBlank() && !containsControlCharacter(it) }
    }

    private fun documentFile(editor: Editor): VirtualFile? =
        FileDocumentManager.getInstance().getFile(editor.document)

    private fun sameFile(first: VirtualFile, second: VirtualFile): Boolean =
        first == second || first.url == second.url

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
        if (text.none { it.isWhitespace() || it == '"' }) {
            return text
        }
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }

    private fun containsControlCharacter(path: String): Boolean = path.any { Character.isISOControl(it) }

    private fun normalizeSeparators(path: String): String = path.replace('\\', '/')
}

/** A complete set of references produced by one action invocation. */
data class LinkPayload(val entries: List<LinkPayloadEntry>)

/** A path reference with an optional one-based inclusive line range. */
data class LinkPayloadEntry(val path: String, val lineRange: LineRange?)

/** A line range; a null end denotes a single-line reference. */
data class LineRange(val start: Int, val end: Int?)
