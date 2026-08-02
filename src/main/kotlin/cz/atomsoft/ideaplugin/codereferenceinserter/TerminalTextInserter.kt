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
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.terminal.JBTerminalWidget
import com.intellij.terminal.ui.TerminalWidget
import com.intellij.ui.content.Content
import org.jetbrains.plugins.terminal.TerminalToolWindowFactory
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import org.jetbrains.plugins.terminal.TerminalView
import java.awt.Component

@Service(Service.Level.PROJECT)
class TerminalTextInserter(private val project: Project) {

    private val logger = logger<TerminalTextInserter>()
    var lastLookupSummary: String = "Terminal lookup has not run yet."
        private set

    fun insertIntoSelectedTerminal(text: String): Boolean {
        return runCatching {
            val terminalManager = TerminalToolWindowManager.getInstance(project)
            val target = findTarget(terminalManager)
            if (target == null) {
                lastLookupSummary = "No target terminal input. $lastLookupSummary"
                return false
            }

            val written = target.write(text)
            if (written) {
                lastLookupSummary = "Inserted into ${target.description}. $lastLookupSummary"
                activateTerminal(target)
            } else {
                lastLookupSummary = "Target found (${target.description}), but no supported write method worked. $lastLookupSummary"
            }
            written
        }.getOrElse {
            logger.warn("Failed to insert text into selected terminal", it)
            lastLookupSummary = "Exception during terminal lookup/write: ${it.javaClass.simpleName}: ${it.message}"
            false
        }
    }

    private fun findTarget(terminalManager: TerminalToolWindowManager): TerminalTarget? {
        val toolWindowManager = ToolWindowManager.getInstance(project)
        val currentToolWindowCandidates = currentToolWindowCandidates(toolWindowManager)
        findTargetInCurrentToolWindow(currentToolWindowCandidates)?.let { return it }

        val toolWindow = terminalToolWindow(terminalManager)
        val selectedContent = toolWindow?.contentManager?.selectedContent
        val modernWidgets = terminalManager.terminalWidgets.toList()
        val legacyWidgets = collectLegacyWidgets(terminalManager)

        lastLookupSummary = "toolWindow=${toolWindow != null}, selectedContent=${selectedContent?.displayName ?: "none"}, modern=${modernWidgets.size}, legacy=${legacyWidgets.size}, current=${describeToolWindows(currentToolWindowCandidates)}"

        if (selectedContent != null) {
            findTargetInContent(selectedContent, toolWindow)?.let { return it }

            modernWidgets.firstOrNull { widget ->
                terminalManager.getContainer(widget)?.content == selectedContent
            }?.let { return widgetTarget(it, toolWindow) }
        }

        if (modernWidgets.size == 1) {
            return widgetTarget(modernWidgets.single(), toolWindow)
        }

        if (legacyWidgets.size == 1) {
            return widgetTarget(legacyWidgets.single().asNewWidget(), toolWindow)
        }

        return null
    }

    private fun findTargetInCurrentToolWindow(toolWindows: List<ToolWindow>): TerminalTarget? {
        for (toolWindow in toolWindows) {
            val selectedContent = toolWindow.contentManager.selectedContent ?: continue
            val target = findTargetInContent(selectedContent, toolWindow) ?: continue
            lastLookupSummary = "toolWindow=${toolWindow.id}, selectedContent=${selectedContent.displayName.ifBlank { "unnamed" }}, current=${describeToolWindows(toolWindows)}"
            return target
        }
        return null
    }

    private fun findTargetInContent(selectedContent: Content, toolWindow: ToolWindow?): TerminalTarget? {
        findTerminalViewBySelectedContent(selectedContent)?.let { terminalView ->
            return senderTarget(
                sender = terminalView,
                description = terminalView.javaClass.name,
                toolWindow = toolWindow,
                focusComponent = invokeNoArg(terminalView, "getPreferredFocusableComponent") as? Component,
            )
        }

        findWidgetByContent(selectedContent)?.let { return widgetTarget(it, toolWindow) }
        findLegacyWidgetByContent(selectedContent)?.let { return legacyWidgetTarget(it, toolWindow) }

        findTerminalObjectInContent(selectedContent)?.let { terminalObject ->
            targetForTerminalObject(terminalObject, toolWindow, selectedContent.component)?.let { return it }
        }

        return null
    }

    private fun widgetTarget(widget: TerminalWidget, toolWindow: ToolWindow?): TerminalTarget =
        TerminalTarget(
            description = widget.javaClass.name,
            toolWindow = toolWindow,
            focus = { widget.requestFocus() },
            write = { text -> writeText(widget, text) },
        )

    private fun legacyWidgetTarget(widget: JBTerminalWidget, toolWindow: ToolWindow?): TerminalTarget =
        TerminalTarget(
            description = widget.javaClass.name,
            toolWindow = toolWindow,
            focus = { widget.asNewWidget().requestFocus() },
            write = { text -> writeText(widget.asNewWidget(), text) },
        )

    private fun senderTarget(
        sender: Any,
        description: String,
        toolWindow: ToolWindow?,
        focusComponent: Component?,
    ): TerminalTarget =
        TerminalTarget(
            description = description,
            toolWindow = toolWindow,
            focus = { focusComponent?.requestFocus() },
            write = { text -> writeTerminalSender(sender, text) },
        )

    private fun targetForTerminalObject(
        terminalObject: Any,
        toolWindow: ToolWindow?,
        focusComponent: Component?,
    ): TerminalTarget? {
        return when (terminalObject) {
            is TerminalWidget -> widgetTarget(terminalObject, toolWindow)
            is JBTerminalWidget -> legacyWidgetTarget(terminalObject, toolWindow)
            else -> if (isTerminalSender(terminalObject)) {
                senderTarget(
                    sender = terminalObject,
                    description = terminalObject.javaClass.name,
                    toolWindow = toolWindow,
                    focusComponent = focusComponent,
                )
            } else {
                null
            }
        }
    }

    private fun currentToolWindowCandidates(toolWindowManager: ToolWindowManager): List<ToolWindow> {
        val ids = LinkedHashSet<String>()
        toolWindowManager.activeToolWindowId?.let { ids.add(it) }
        toolWindowManager.lastActiveToolWindowId?.let { ids.add(it) }

        toolWindowManager.toolWindowIds
            .filterNot { it == TerminalToolWindowFactory.TOOL_WINDOW_ID }
            .forEach { id ->
                val toolWindow = toolWindowManager.getToolWindow(id)
                if (toolWindow?.isVisible == true) {
                    ids.add(id)
                }
            }

        toolWindowManager.toolWindowIds
            .filter { it == TerminalToolWindowFactory.TOOL_WINDOW_ID }
            .forEach { id ->
                val toolWindow = toolWindowManager.getToolWindow(id)
                if (toolWindow?.isVisible == true) {
                    ids.add(id)
                }
            }

        return ids.mapNotNull { toolWindowManager.getToolWindow(it) }
    }

    private fun describeToolWindows(toolWindows: List<ToolWindow>): String =
        toolWindows.joinToString(prefix = "[", postfix = "]") { toolWindow ->
            val contentName = toolWindow.contentManager.selectedContent?.displayName?.ifBlank { "unnamed" } ?: "none"
            "${toolWindow.id}:$contentName"
        }

    private fun findTerminalViewBySelectedContent(selectedContent: Content): Any? {
        return runCatching {
            val managerClass = Class.forName("com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager")
            val getInstance = managerClass.methods.firstOrNull { method ->
                method.name == "getInstance" && method.parameterCount == 1 && method.parameterTypes[0].isAssignableFrom(Project::class.java)
            } ?: return null
            val manager = getInstance.invoke(null, project) ?: return null
            val tabs = invokeNoArg(manager, "getTabs") as? Iterable<*> ?: return null
            tabs.firstNotNullOfOrNull { tab ->
                val content = invokeNoArg(tab, "getContent")
                if (content == selectedContent) invokeNoArg(tab, "getView") else null
            }
        }.getOrElse {
            logger.debug("Failed to inspect TerminalToolWindowTabsManager", it)
            null
        }
    }

    private fun findTerminalObjectInContent(content: Content): Any? {
        findTerminalObject(content)?.let { return it }
        content.component?.let { component ->
            terminalComponents(component).forEach { child ->
                findTerminalObject(child)?.let { return it }
            }
        }
        return null
    }

    private fun findTerminalSenderInContent(content: Content): Any? {
        return findTerminalObjectInContent(content)?.takeIf { isTerminalSender(it) }
    }

    private fun terminalComponents(root: Component): Sequence<Component> = sequence {
        yield(root)
        if (root is java.awt.Container) {
            root.components.forEach { child ->
                yieldAll(terminalComponents(child))
            }
        }
    }

    private fun terminalToolWindow(terminalManager: TerminalToolWindowManager): ToolWindow? =
        terminalManager.getToolWindow()
            ?: ToolWindowManager.getInstance(project).getToolWindow(TerminalToolWindowFactory.TOOL_WINDOW_ID)

    private fun findWidgetByContent(content: Content): TerminalWidget? {
        return runCatching {
            TerminalToolWindowManager.findWidgetByContent(content)
        }.getOrElse {
            logger.warn("Failed to find terminal widget by selected content", it)
            null
        }
    }

    private fun findLegacyWidgetByContent(content: Content): JBTerminalWidget? {
        return runCatching {
            TerminalToolWindowManager.getWidgetByContent(content)
        }.getOrElse {
            logger.warn("Failed to find legacy terminal widget by selected content", it)
            null
        }
    }

    private fun collectLegacyWidgets(terminalManager: TerminalToolWindowManager): List<JBTerminalWidget> {
        val widgets = LinkedHashSet<JBTerminalWidget>()
        runCatching {
            widgets.addAll(terminalManager.widgets)
        }.onFailure {
            logger.warn("Failed to inspect legacy terminal widgets from manager", it)
        }

        runCatching {
            widgets.addAll(TerminalView.getInstance(project).getWidgets())
        }.onFailure {
            logger.warn("Failed to inspect legacy terminal widgets from TerminalView", it)
        }

        return widgets.toList()
    }

    private fun writeText(widget: TerminalWidget, text: String): Boolean {
        if (invokeTerminalInputSendString(widget, text)) {
            return true
        }

        if (invokeStringMethod(widget, "sendText", text)) {
            return true
        }

        if (writeThroughConnector(widget, text)) {
            return true
        }

        if (invokeStringMethod(widget, "typeText", text)) {
            return true
        }

        if (invokeStringMethod(widget, "pasteText", text)) {
            return true
        }

        return false
    }

    private fun writeTerminalSender(sender: Any, text: String): Boolean {
        if (sender.javaClass.methods.any { it.name == "sendText" && it.parameterCount == 1 && it.parameterTypes[0] == String::class.java }) {
            return invokeStringMethod(sender, "sendText", text)
        }
        if (sender.javaClass.methods.any { it.name == "sendString" && it.parameterCount == 1 && it.parameterTypes[0] == String::class.java }) {
            return invokeStringMethod(sender, "sendString", text)
        }
        if (sender.javaClass.methods.any { it.name == "sendBytes" && it.parameterCount == 1 && it.parameterTypes[0] == ByteArray::class.java }) {
            return invokeByteArrayMethod(sender, "sendBytes", text.toByteArray(Charsets.UTF_8))
        }
        return false
    }

    private fun invokeTerminalInputSendString(widget: TerminalWidget, text: String): Boolean {
        val terminalInput = findTerminalInput(widget) ?: return false
        return writeTerminalSender(terminalInput, text)
    }

    private fun findTerminalInput(widget: TerminalWidget): Any? {
        return findTerminalSender(widget)
    }

    private fun findTerminalObject(source: Any?): Any? {
        val seen = mutableSetOf<Int>()
        return findTerminalObject(source, seen, maxDepth = 7)
    }

    private fun findTerminalObject(
        source: Any?,
        seen: MutableSet<Int>,
        maxDepth: Int,
    ): Any? {
        if (source == null || maxDepth < 0) {
            return null
        }

        val identity = System.identityHashCode(source)
        if (!seen.add(identity)) {
            return null
        }

        if (isTerminalObject(source)) {
            return source
        }

        userDataValues(source).forEach { value ->
            if (isTerminalObject(value)) {
                return value
            }
            if (shouldDescendInto(value.javaClass)) {
                findTerminalObject(value, seen, maxDepth - 1)?.let { return it }
            }
        }

        for (field in allFields(source.javaClass)) {
            val value = runCatching {
                field.isAccessible = true
                field.get(source)
            }.getOrNull() ?: continue

            if (isTerminalObject(value)) {
                return value
            }

            if (shouldDescendInto(value.javaClass)) {
                findTerminalObject(value, seen, maxDepth - 1)?.let { return it }
            }
        }

        return null
    }

    private fun findTerminalSender(source: Any?): Any? {
        return findTerminalObject(source)?.takeIf { isTerminalSender(it) }
    }

    private fun isTerminalSender(value: Any): Boolean {
        val className = value.javaClass.name
        val isTerminalObject = className.contains(".terminal.", ignoreCase = true) ||
            className.contains("Terminal", ignoreCase = false)
        if (!isTerminalObject) {
            return false
        }

        return value.javaClass.methods.any { method ->
            (method.name == "sendString" || method.name == "sendText") &&
                method.parameterCount == 1 &&
                method.parameterTypes[0] == String::class.java
        } || value.javaClass.methods.any { method ->
            method.name == "sendBytes" &&
            method.parameterCount == 1 &&
            method.parameterTypes[0] == ByteArray::class.java
        }
    }

    private fun isTerminalObject(value: Any): Boolean =
        value is TerminalWidget ||
            value is JBTerminalWidget ||
            isTerminalSender(value)

    private fun shouldDescendInto(type: Class<*>): Boolean {
        val name = type.name
        return TerminalWidget::class.java.isAssignableFrom(type) ||
            JBTerminalWidget::class.java.isAssignableFrom(type) ||
            name.startsWith("com.intellij.terminal.") ||
            name.startsWith("org.jetbrains.plugins.terminal.") ||
            name.startsWith("com.jediterm.") ||
            name.startsWith("com.intellij.ui.content.") ||
            name.startsWith("com.intellij.openapi.editor.") ||
            name.startsWith("com.intellij.openapi.fileEditor.") ||
            name.startsWith("com.intellij.openapi.util.") ||
            name.startsWith("com.intellij.util.keyFMap.")
    }

    private fun allFields(type: Class<*>): Sequence<java.lang.reflect.Field> = sequence {
        var current: Class<*>? = type
        while (current != null && current != Any::class.java) {
            yieldAll(current.declaredFields.asSequence())
            current = current.superclass
        }
    }

    private fun allMethods(type: Class<*>): Sequence<java.lang.reflect.Method> = sequence {
        var current: Class<*>? = type
        while (current != null && current != Any::class.java) {
            yieldAll(current.declaredMethods.asSequence())
            current = current.superclass
        }
    }

    private fun userDataValues(source: Any): Sequence<Any> = sequence {
        if (source !is UserDataHolderBase) {
            return@sequence
        }

        val userMap = runCatching {
            val method = allMethods(source.javaClass).firstOrNull { it.name == "getUserMap" && it.parameterCount == 0 }
                ?: return@sequence
            method.isAccessible = true
            method.invoke(source)
        }.getOrNull() ?: return@sequence

        val keys = runCatching {
            invokeNoArg(userMap, "getKeys") as? Array<*>
        }.getOrNull() ?: return@sequence

        val getMethod = userMap.javaClass.methods.firstOrNull { method ->
            method.name == "get" && method.parameterCount == 1
        } ?: return@sequence

        keys.filterNotNull().forEach { key ->
            val value = runCatching {
                getMethod.isAccessible = true
                getMethod.invoke(userMap, key)
            }.getOrNull()
            if (value != null) {
                yield(value)
            }
        }
    }

    private fun activateTerminal(target: TerminalTarget) {
        runCatching {
            target.toolWindow?.activate({ target.focus() }, true)
                ?: target.focus()
        }.onFailure {
            logger.warn("Failed to request focus for terminal", it)
        }
    }

    private fun writeThroughConnector(widget: TerminalWidget, text: String): Boolean {
        val connector = runCatching { widget.ttyConnector }.getOrNull() ?: return false
        return runCatching {
            connector.write(text)
            true
        }.getOrElse {
            logger.warn("Failed to write through terminal connector", it)
            false
        }
    }

    private fun invokeStringMethod(target: Any, methodName: String, text: String): Boolean {
        val method = target.javaClass.methods.firstOrNull { method ->
            method.name == methodName &&
                method.parameterCount == 1 &&
                method.parameterTypes[0] == String::class.java
        } ?: return false

        return runCatching {
            method.isAccessible = true
            method.invoke(target, text)
            true
        }.getOrElse {
            logger.warn("Failed to invoke $methodName on ${target.javaClass.name}", it)
            false
        }
    }

    private fun invokeByteArrayMethod(target: Any, methodName: String, bytes: ByteArray): Boolean {
        val method = target.javaClass.methods.firstOrNull { method ->
            method.name == methodName &&
                method.parameterCount == 1 &&
                method.parameterTypes[0] == ByteArray::class.java
        } ?: return false

        return runCatching {
            method.isAccessible = true
            method.invoke(target, bytes)
            true
        }.getOrElse {
            logger.warn("Failed to invoke $methodName on ${target.javaClass.name}", it)
            false
        }
    }

    private fun invokeNoArg(target: Any?, methodName: String): Any? {
        if (target == null) return null
        val method = target.javaClass.methods.firstOrNull { method ->
            method.name == methodName && method.parameterCount == 0
        } ?: return null
        return runCatching {
            method.isAccessible = true
            method.invoke(target)
        }.getOrNull()
    }

    private data class TerminalTarget(
        val description: String,
        val toolWindow: ToolWindow?,
        val focus: () -> Unit,
        val write: (String) -> Boolean,
    )
}
