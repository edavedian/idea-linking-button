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

import com.intellij.openapi.editor.impl.DocumentImpl
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinkPayloadResolverTest : BasePlatformTestCase() {

    fun testEditorSelectionResolvesRelativePathAndLines() {
        myFixture.configureByText(
            "Foo.kt",
            """
            class Foo {
                fun bar() {
                    <selection>val x = 1
                    val y = 2</selection>
                }
            }
            """.trimIndent(),
        )

        val payload = LinkPayloadResolver.resolve(
            project = project,
            editor = myFixture.editor,
            fallbackFile = myFixture.file.virtualFile,
        )

        val resolved = requireNotNull(payload)
        val formatted = LinkPayloadResolver.formatInsertText(resolved)
        assertTrue(formatted.endsWith("Foo.kt:3-4 "))
    }

    fun testEditorWithoutSelectionResolvesFileOnly() {
        myFixture.configureByText(
            "Foo.kt",
            """
            class Foo {
                fun bar() {
                    val x = 1
                }
            }
            """.trimIndent(),
        )

        val payload = LinkPayloadResolver.resolve(
            project = project,
            editor = myFixture.editor,
            fallbackFile = myFixture.file.virtualFile,
        )

        val resolved = requireNotNull(payload)
        val formatted = LinkPayloadResolver.formatInsertText(resolved)
        assertTrue(formatted.endsWith("Foo.kt "))
    }

    fun testMultipleProjectViewSelectionsAreSpaceSeparated() {
        val first = myFixture.addFileToProject("src/Foo.kt", "class Foo").virtualFile
        val second = myFixture.addFileToProject("src/nested/Bar.kt", "class Bar").virtualFile

        val payload = LinkPayloadResolver.resolve(project = project, virtualFiles = listOf(first, second))

        val resolved = requireNotNull(payload)
        assertEquals(2, resolved.entries.size)
        assertTrue(resolved.entries[0].path.endsWith("src/Foo.kt"))
        assertTrue(resolved.entries[1].path.endsWith("src/nested/Bar.kt"))
    }

    fun testExplicitProjectViewFileWinsOverEditorSelection() {
        myFixture.configureByText(
            "Editor.kt",
            """
            class Editor {
                <selection>fun oldSelection() = true</selection>
            }
            """.trimIndent(),
        )
        val projectViewFile = myFixture.addFileToProject("src/ProjectView.kt", "class ProjectView").virtualFile

        val payload = LinkPayloadResolver.resolve(
            project = project,
            editor = myFixture.editor,
            virtualFiles = listOf(projectViewFile),
            explicitFileSelection = true,
        )

        val resolved = requireNotNull(payload)
        assertEquals(1, resolved.entries.size)
        assertTrue(resolved.entries.single().path.replace('\\', '/').endsWith("src/ProjectView.kt"))
        assertNull(resolved.entries.single().lineRange)
    }

    fun testExplicitFallbackFileSuppressesEditorSelection() {
        myFixture.configureByText(
            "Editor.kt",
            """
            class Editor {
                <selection>fun selected() = true</selection>
            }
            """.trimIndent(),
        )

        val payload = LinkPayloadResolver.resolve(
            project = project,
            editor = myFixture.editor,
            fallbackFile = myFixture.file.virtualFile,
            explicitFileSelection = true,
        )

        val resolved = requireNotNull(payload)
        assertEquals(1, resolved.entries.size)
        assertTrue(resolved.entries.single().path.replace('\\', '/').endsWith("Editor.kt"))
        assertNull(resolved.entries.single().lineRange)
    }

    fun testDerivedSingleEditorVirtualFileKeepsEditorSelection() {
        myFixture.configureByText(
            "Editor.kt",
            """
            class Editor {
                <selection>fun selected() = true</selection>
            }
            """.trimIndent(),
        )

        val payload = LinkPayloadResolver.resolve(
            project = project,
            editor = myFixture.editor,
            virtualFiles = listOf(myFixture.file.virtualFile),
        )

        val resolved = requireNotNull(payload)
        assertEquals(1, resolved.entries.size)
        assertTrue(resolved.entries.single().path.replace('\\', '/').endsWith("Editor.kt"))
        assertEquals(LineRange(2, null), resolved.entries.single().lineRange)
    }

    fun testFallbackFileDoesNotUseDifferentEditorDocumentSelection() {
        myFixture.configureByText(
            "Editor.kt",
            """
            class Editor {
                <selection>fun oldSelection() = true</selection>
            }
            """.trimIndent(),
        )
        val fallbackFile = myFixture.addFileToProject("src/Fallback.kt", "class Fallback").virtualFile

        val payload = LinkPayloadResolver.resolve(
            project = project,
            editor = myFixture.editor,
            fallbackFile = fallbackFile,
        )

        val resolved = requireNotNull(payload)
        assertEquals(1, resolved.entries.size)
        assertTrue(resolved.entries.single().path.replace('\\', '/').endsWith("src/Fallback.kt"))
        assertNull(resolved.entries.single().lineRange)
    }

    fun testPathWithSpacesIsQuoted() {
        val payload = LinkPayload(
            listOf(LinkPayloadEntry("src/with space/Foo.kt", LineRange(12, 18))),
        )

        assertEquals("\"src/with space/Foo.kt:12-18\" ", LinkPayloadResolver.formatInsertText(payload))
    }

    fun testPathWithQuoteIsQuotedAndEscaped() {
        val payload = LinkPayload(
            listOf(LinkPayloadEntry("src/with\"quote/Foo.kt", null)),
        )

        assertEquals("\"src/with\\\"quote/Foo.kt\" ", LinkPayloadResolver.formatInsertText(payload))
    }

    fun testControlCharacterPathIsRejected() {
        val file = LightVirtualFile("bad\nname.kt", PlainTextFileType.INSTANCE, "class Bad")

        assertNull(LinkPayloadResolver.resolve(project = project, virtualFiles = listOf(file)))
    }

    fun testDisplayPathIsRelativeToProjectBase() {
        val projectBase = Path.of("work", "project").toAbsolutePath().normalize()
        val descendant = projectBase.resolve("src").resolve("Foo.kt")

        assertEquals(
            "src/Foo.kt",
            LinkPayloadResolver.resolveDisplayPath(projectBase.toString(), descendant.toString()),
        )
    }

    fun testDisplayPathForProjectRootIsAbsolute() {
        val projectBase = Path.of("work", "project").toAbsolutePath().normalize()

        assertEquals(
            projectBase.toString().replace('\\', '/'),
            LinkPayloadResolver.resolveDisplayPath(projectBase.toString(), projectBase.toString()),
        )
    }

    fun testNonLocalDisplayPathIsKeptAsReference() {
        val projectBase = Path.of("work", "project").toAbsolutePath().normalize()

        assertEquals(
            "jar://lib/library.jar!/Foo.class",
            LinkPayloadResolver.resolveDisplayPath(projectBase.toString(), "jar://lib/library.jar!/Foo.class"),
        )
    }

    fun testNonEmptySelectionEndingAtDocumentLengthExcludesTrailingEmptyLine() {
        val document = DocumentImpl("first\nsecond\n")

        assertEquals(LineRange(1, 2), LinkPayloadResolver.toLineRange(document, 0, document.textLength))
    }

    fun testLineRangeRejectsOutOfBoundsOffsets() {
        val document = DocumentImpl("first\nsecond")

        assertNull(LinkPayloadResolver.toLineRange(document, -1, 1))
        assertNull(LinkPayloadResolver.toLineRange(document, 1, document.textLength + 1))
        assertNull(LinkPayloadResolver.toLineRange(document, document.textLength + 1, document.textLength + 1))
        assertNull(LinkPayloadResolver.toLineRange(document, 4, 3))
    }
}
