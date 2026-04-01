# 🕵️ CommandSpy (Hytale Server)

CommandSpy is a lightweight moderation utility for Hytale Server that allows administrators to monitor player commands in real time.

Designed to be fast, lightweight, and fully configurable, CommandSpy helps staff monitor player activity without impacting server performance.


## ✨ Features

✔ Real-time command spy  
✔ Toggle per-admin using /commandspy  
✔ Permission based (command.spy)  
✔ Persistent admin settings  
✔ Configurable message format  
✔ Command logging to file  
✔ Default OFF for admins  
✔ Configurable command filters  
✔ Hytale Server v4 compatible  


## 🔎 Command Filters

CommandSpy supports filtering commands shown to administrators:

**Private messages**  
/msg, /tell, /w, /whisper, /r, /reply, /mail send  

**Console commands**

**Authentication commands**  
/login, /register  

**Important:**  
Filters only affect in-game visibility.  
commands.log continues to record all commands.


## 📜 Commands

/commandspy  
/cmdspy  

Toggle command spy for the executing admin.


## 🔐 Permission

command.spy

Only users with this permission can use CommandSpy.


## 💬 Chat Format

Default format:

&a[SPY] &7%player%: %command%

Placeholders:

%player%  
%command%


## 📁 Generated Files

CommandSpy creates:

CommandSpy/  
config.json  
spy-state.json  
commands.log  


## 📝 Command Logging

All intercepted commands are saved to:

CommandSpy/commands.log

Example:

[2026-03-30 11:47:20] Player: home house


## ⚙️ Configuration

Example config.json:

```json
{
  "message-format": "&a[SPY] &7%player%: %command%",
  "spy-enabled-message": "&a[SPY] &7Command spy enabled.",
  "spy-disabled-message": "&c[SPY] &7Command spy disabled.",
  "default-spy-enabled": false,
  "spy-private-messages": true,
  "spy-console-commands": true,
  "spy-auth-commands": true
}
```


## 📦 Installation

1. Download CommandSpy.jar  
2. Place it inside your server mods folder  
3. Restart the server  
4. Assign the permission `command.spy`  

---

## ✅ Compatibility

✔ Hytale Server v4  
✔ Lightweight and production-ready  

---

## 💙 Inspiration

Inspired by command spy tools from the Minecraft server community, such as **CommandSpy**, **SocialSpy**, and similar moderation utilities.

After waiting for a similar tool for Hytale, I decided to create one myself — both out of necessity and as a tribute to the original concept.
