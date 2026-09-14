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
code reference into the selected standard Terminal tab. It is intended for
workflows where an IDE action should paste the current file, folder, or selected
code range directly into a running shell or command-line tool.

The development build targets IntelliJ IDEA 2025.2.4 and requires the bundled
JetBrains Terminal plugin. Other JetBrains IDEs, including Android Studio, need
separate runtime verification. If the terminal cannot be written to, the same
text is copied to the IDE clipboard.

## Features

- Inserts the active editor file path.
- Inserts the active editor file path with selected line range.
- Inserts selected files or folders from the Project View.
- Writes into the selected standard Terminal tool window tab when possible.
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

The inserted value is project-relative when possible, so it does not depend on
the terminal process's current working directory. Examples:

```text
src/main/kotlin/example/Foo.kt
src/main/kotlin/example/Foo.kt:12
src/main/kotlin/example/Foo.kt:12-18
"src/main/kotlin/example folder"
```

The plugin transfers this text into the terminal or clipboard; it does not
execute a command. The quoted form is a display and transfer format, not a
guarantee of shell escaping for every command-line tool.

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
4. Select `build/distributions/idea-code-reference-inserter-plugin-0.9.1.zip`.

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
build/distributions/idea-code-reference-inserter-plugin-0.9.1.zip
```

## Continuous integration and releases

Every branch push and pull request runs the tests, plugin build, and plugin
structure checks through GitHub Actions. Pushing a tag such as `1.0.0` runs the
same checks with that tag as the plugin version and creates a GitHub Release with
the generated ZIP attached.

Plugin ID:

```text
cz.atomsoft.ideaplugin.code-reference-inserter
```

## Compatibility Notes

JetBrains provides the reworked Terminal API starting with 2025.3, while this
project's development target is IntelliJ IDEA 2025.2.4. The plugin therefore
uses a narrow compatibility bridge for the supported standard Terminal tab and
keeps clipboard copy as a safe fallback. See the
[JetBrains Embedded Terminal API documentation](https://plugins.jetbrains.com/docs/intellij/embedded-terminal.html)
for the platform API and its version status.

If a future IDE version changes its internal Terminal API, the plugin keeps the
reference available through the clipboard and shows a concise fallback notification.

## License

Licensed under the GNU General Public License, version 3. See [LICENSE](LICENSE).
