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

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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

        assertNotNull(payload)
        payload!!
        val formatted = LinkPayloadResolver.formatInsertText(payload)
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

        assertNotNull(payload)
        payload!!
        val formatted = LinkPayloadResolver.formatInsertText(payload)
        assertTrue(formatted.endsWith("Foo.kt "))
    }

    fun testMultipleProjectViewSelectionsAreSpaceSeparated() {
        val first = myFixture.addFileToProject("src/Foo.kt", "class Foo").virtualFile
        val second = myFixture.addFileToProject("src/nested/Bar.kt", "class Bar").virtualFile

        val payload = LinkPayloadResolver.resolve(project = project, virtualFiles = listOf(first, second))

        assertNotNull(payload)
        payload!!
        assertEquals(2, payload.entries.size)
        assertTrue(payload.entries[0].path.endsWith("src/Foo.kt"))
        assertTrue(payload.entries[1].path.endsWith("src/nested/Bar.kt"))
    }

    fun testPathWithSpacesIsQuoted() {
        val payload = LinkPayload(
            listOf(LinkPayloadEntry("src/with space/Foo.kt", LineRange(12, 18))),
        )

        assertEquals("\"src/with space/Foo.kt:12-18\" ", LinkPayloadResolver.formatInsertText(payload))
    }

    fun testDisplayPathIsRelativeToProjectBase() {
        assertEquals(
            "src/Foo.kt",
            LinkPayloadResolver.resolveDisplayPath("C:/work/project", "C:/work/project/src/Foo.kt"),
        )
    }
}
