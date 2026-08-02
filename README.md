<!--
Copyright (C) 2026 ATomSoft

This file is part of Code Reference Inserter.

Code Reference Inserter is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, version 3.

Code Reference Inserter is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with Code Reference Inserter. If not, see <https://www.gnu.org/licenses/>.
-->

# Code Reference Inserter

Code Reference Inserter is a JetBrains IDE plugin for quickly inserting a useful
code reference into the currently selected Terminal tab. It is intended for
workflows where an IDE action should paste the current file, folder, or selected
code range directly into a running shell or command-line tool.

The plugin supports IntelliJ IDEA and Android Studio, and should also work in
other JetBrains IDEs that bundle the JetBrains Terminal plugin. If the terminal
cannot be written to, the same text is copied to the IDE clipboard.

## Features

- Inserts the active editor file path.
- Inserts the active editor file path with selected line range.
- Inserts selected files or folders from the Project View.
- Writes into the selected Terminal tool window tab when possible.
- Falls back to the IDE clipboard when no supported terminal target is available.
- Registers the **Insert Code Reference** action in the main toolbar, navigation
  bar toolbar, Tools menu, editor context menu, editor tab context menu, and
  Project View context menu.

## Usage

1. Open a project in a supported JetBrains IDE.
2. Open the Terminal tool window and select the tab that should receive text.
3. Select a file, folder, or code range.
4. Run **Insert Code Reference** from the main toolbar, navigation bar toolbar,
   Tools menu, editor context menu, editor tab context menu, Project View
   context menu, or an assigned keyboard shortcut.

The inserted value is project-relative when possible. Examples:

```text
src/main/kotlin/example/Foo.kt
src/main/kotlin/example/Foo.kt:12
src/main/kotlin/example/Foo.kt:12-18
"src/main/kotlin/example folder"
```

## Keyboard Shortcut

The plugin does not force a default shortcut. Assign one in:

```text
Settings | Keymap | Plugins | Code Reference Inserter | Insert Code Reference
```

The action name shown in Keymap is **Insert Code Reference**.

## Installation From ZIP

1. Build or download the plugin ZIP.
2. In the IDE, open **Settings | Plugins**.
3. Choose **Install Plugin from Disk...**.
4. Select `build/distributions/idea-code-reference-inserter-plugin-0.9.0.zip`.

## Development

Requirements:

- JDK 21
- Gradle wrapper from this repository

Common commands:

```powershell
.\gradlew.bat test
.\gradlew.bat buildPlugin
.\gradlew.bat runIde
```

The generated plugin ZIP is written to:

```text
build/distributions/idea-code-reference-inserter-plugin-0.9.0.zip
```

Plugin ID:

```text
cz.atomsoft.ideaplugin.code-reference-inserter
```

## Compatibility Notes

JetBrains does not currently expose one stable public API for inserting text
into every Terminal implementation. The plugin therefore uses several
version-specific terminal lookup paths and keeps clipboard copy as a safe
fallback.

If a future IDE version changes its internal Terminal API, the notification
shown by the plugin includes diagnostic details that can be used to add support.

## License

Licensed under the GNU General Public License, version 3. See [LICENSE](LICENSE).
