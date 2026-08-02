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

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import java.awt.datatransfer.StringSelection

class InsertCodeReferenceAction : AnAction(
    "Insert Code Reference",
    "Insert the current file or selection reference into the active terminal",
    IconLoader.getIcon("/icons/linking.svg", InsertCodeReferenceAction::class.java),
), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val payload = LinkPayloadResolver.resolve(
            project = project,
            editor = e.getData(CommonDataKeys.EDITOR),
            virtualFiles = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.toList().orEmpty(),
            fallbackFile = e.getData(CommonDataKeys.VIRTUAL_FILE),
        )

        if (payload == null) {
            notify(project, "No file, folder, or editor context found.", NotificationType.INFORMATION)
            return
        }

        val text = LinkPayloadResolver.formatInsertText(payload)
        val terminalTextInserter = project.service<TerminalTextInserter>()
        if (terminalTextInserter.insertIntoSelectedTerminal(text)) {
            return
        }

        CopyPasteManager.getInstance().setContents(StringSelection(text))
        notify(
            project,
            "Could not insert into terminal. Link copied to clipboard. ${terminalTextInserter.lastLookupSummary}",
            NotificationType.INFORMATION,
        )
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    private fun notify(project: Project, content: String, type: NotificationType) {
        val group = NotificationGroupManager.getInstance().getNotificationGroup("CodeReferenceInserter")
        group.createNotification("Code Reference Inserter", content, type).notify(project)
    }
}
