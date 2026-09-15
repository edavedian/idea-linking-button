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

import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Orders tool windows by how likely they are to be the user's intended send target:
 * active, then last-active, then other visible windows, with [deprioritizedIds] considered last.
 */
internal object ToolWindowCandidateOrder {

    fun current(toolWindowManager: ToolWindowManager, deprioritizedIds: Set<String>): List<ToolWindow> {
        val ids = LinkedHashSet<String>()
        toolWindowManager.activeToolWindowId?.let { ids.add(it) }
        toolWindowManager.lastActiveToolWindowId?.let { ids.add(it) }

        toolWindowManager.toolWindowIds
            .filterNot { it in deprioritizedIds }
            .forEach { id ->
                val toolWindow = toolWindowManager.getToolWindow(id)
                if (toolWindow?.isVisible == true) {
                    ids.add(id)
                }
            }

        toolWindowManager.toolWindowIds
            .filter { it in deprioritizedIds }
            .forEach { id ->
                val toolWindow = toolWindowManager.getToolWindow(id)
                if (toolWindow?.isVisible == true) {
                    ids.add(id)
                }
            }

        return ids.mapNotNull { toolWindowManager.getToolWindow(it) }
    }

    fun describe(toolWindows: List<ToolWindow>): String =
        toolWindows.joinToString(prefix = "[", postfix = "]") { toolWindow ->
            val contentName = toolWindow.contentManager.selectedContent?.displayName?.ifBlank { "unnamed" } ?: "none"
            "${toolWindow.id}:$contentName"
        }
}
