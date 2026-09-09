# CommandSpy

CommandSpy is a staff-monitoring plugin for Hytale Server 0.6.x. It shows
executed player commands, console commands and private-message commands in real
time, with independent permissions and personal filters for every staff member.
It can also maintain a local audit log with automatic time-based retention.

## Features

- Player-command monitoring
- Console-command monitoring
- Private-message monitoring
- Independent permissions for every category
- Master permission for full access
- Personal general ON/OFF switch
- Personal ON/OFF filter for each category
- Persistent settings only for staff with CommandSpy currently enabled
- Compact control panel opened with `/cmdspy gui`
- Independent audit-log options for each category
- Single persistent `commands.log` audit file
- Automatic removal of audit entries older than the configured retention period
- Default audit-log retention: 15 days
- Bundled multilingual UI and chat messages
- No external data transmission
- Credential-bearing commands are excluded from live monitoring and audit logs

## Installation

1. Stop the Hytale server.
2. Copy `CommandSpy-2.1.1.jar` into the server `mods` folder.
3. Start the server.
4. Grant the required permissions to staff members.

CommandSpy creates and uses:

```text
mods/CommandSpy/config.json
mods/CommandSpy/spy-state.json
mods/CommandSpy/console-state.json
mods/CommandSpy/logs/commands.log
```

Older timestamped CommandSpy audit logs are merged into `commands.log` when the
new logging system is initialized, then the old per-session files are removed.

## Permissions

### Full access

```text
command.spy
```

Grants access to every monitoring category and to `/cmdspy reload`.

### Private messages

```text
command.spy.messages
```

Allows the staff member to view private-message commands. Recognized commands
include `/msg`, `/m`, `/message`, `/pm`, `/tell`, `/w`, `/whisper`, `/r`,
`/reply` and `/mail send`.

### Player commands

```text
command.spy.commands
```

Allows the staff member to view commands executed by players. Private-message
commands are classified separately and require `command.spy.messages`.

### Console commands

```text
command.spy.console
```

Allows the staff member to view commands executed by the server console.

Permissions are additive. Example assignments:

```text
Moderator:        command.spy.commands
Senior moderator: command.spy.commands + command.spy.messages
Administrator:    command.spy.commands + command.spy.messages + command.spy.console
Owner:            command.spy
```

## Commands

```text
/cmdspy
/commandspy
```

Enables or disables the personal general spy switch.

```text
/cmdspy messages
```

Toggles private-message monitoring.

```text
/cmdspy commands
```

Toggles player-command monitoring.

```text
/cmdspy console
```

Toggles console-command monitoring.

```text
/cmdspy gui
```

Opens the control panel. Categories remain visible even without permission, but
their buttons cannot be used.

```text
/cmdspy reload
```

Reloads the configuration and saved staff states. Requires `command.spy`.

## Control panel

The panel contains:

```text
GENERAL SPY

Filters without permission remain visible but cannot be changed.

PRIVATE MESSAGES
PLAYER COMMANDS
CONSOLE
```

The selected ON/OFF option uses the primary button style. The unselected option
uses the secondary style. Changes made in the panel are saved immediately.

## Personal settings

CommandSpy persists state only for staff members whose general spy switch is
currently enabled. Ordinary players and staff members with CommandSpy disabled
are not kept in the saved state files.

When the general spy switch is disabled, that UUID is removed from the saved
CommandSpy state and from the saved console-filter state. Category defaults are
restored for the next activation.

## Configuration

Default `config.json`:

```jsonc
{
  // Automatically enable CommandSpy for authorized staff on first use/join
  "default-spy-enabled": false,

  // Save regular commands executed by players
  "log-player-commands": true,

  // Save private-message commands under logs/commands.log
  // (/msg, /tell, /w, /whisper, /r, /reply, /mail send)
  "log-private-messages": true,

  // Save commands executed by the console
  "log-console-commands": true,

  // Keep audit-log entries for this many days
  "log-retention-days": 15
}
```

The logging options affect only the local audit file. They do not grant live
monitoring permissions and do not change a staff member's personal filters.
Each logging category can be enabled or disabled independently.

`log-retention-days` is preserved when `/cmdspy reload` or a server restart
rewrites the configuration. Invalid or non-positive values fall back to 15 days.

## Audit logs

CommandSpy keeps one audit file:

```text
mods/CommandSpy/logs/commands.log
```

Audit entries contain a timestamp, the executor and the executed command. On
startup and periodically while new entries are written, lines older than the
configured retention period are removed automatically. The default retention is
15 days.

The subscriber queue is drained during shutdown so final console commands can be
written before the plugin closes.

## Command capture

CommandSpy listens to executed-command records from Hytale's public logger
subscription API. Records are classified as:

- private-message commands;
- regular player commands;
- console commands.

Only records confirming command execution are processed. Duplicate delivery of
the same log record is suppressed without blocking a command that is genuinely
executed again later.

## Credential protection

Commands commonly used for login, registration, password changes, PINs, one-time
codes and similar credentials are ignored before classification. Their arguments
are not shown to staff and are not written to CommandSpy audit logs.

## Languages and colors

The JAR includes complete bundled translations for:

- English
- Italian
- German
- French
- Spanish
- Brazilian Portuguese
- Russian

Chat messages support `&0` through `&f`, `&r`, and hexadecimal colors such as
`&#55FF55`. The bundled live-spy format is defined by:

```text
commandspy.chat.spy-format = &f[SPY] {player}: /{command}
```

## Privacy and file access

CommandSpy does not send monitored data outside the server. Audit logs can
contain command arguments and private conversations, so access to
`mods/CommandSpy` should be restricted to trusted administrators.

## Compatibility

- Hytale Server: `>=0.6.0 <0.7.0`
- Verified base functionality on Hytale Server 0.6.1
- Java: 21
- Required dependencies: none
