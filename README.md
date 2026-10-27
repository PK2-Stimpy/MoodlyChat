# MoodlyChat 1.0.0-beta.1

Simple chat, super secure, easy to use.

End-to-end encrypted chat with channels, voice messages, and file sharing.  
The server only relays data - it never sees your messages, keys, or files.

## Features

- **End-to-end encryption** - AES-256-GCM with PBKDF2-HMAC-SHA256 (1,000,000 iterations for text/files/voice messages)
- **Channels with secret keys** - keys stay only on the clients
- **Multiple users per channel** - people with different keys on the same channel only see messages they can decrypt
- **Voice messages** - record and send encrypted audio (can be disabled)
- **File sharing** - send files up to 50 MB (encrypted). Recipients choose whether to accept them
- **Configurable receive limit** - set your own max file size (default 10 MB)
- **Autoplay** - optionally preview images or open videos when you accept a file
- **User count notifications** - see when people join or leave a channel
- **Old-school GUI** - looks cool :)

## How it works

1. Connect to a server (host + port) and set a nickname (local only).
2. Join a channel with a secret key (only you and people you share the key with can read messages).
3. Send text, voice messages, or files. Everything is encrypted before it leaves your machine.
4. The server just forwards packets to everyone in the channel. Wrong key = message is ignored.

## Requirements

- **Java 17** or newer

## Building

1. Run any of both **build.bat** *(windows)* or **build.sh** *(linux/mac)*

## Running

> **Note:** Run the commands from inside the `build` folder.

Received files are saved in a `downloads` folder next to the program (created automatically).

### Server
```bash
java ChatServer [port]
```

Default port: 5555 (UDP audio uses port+1 if enabled).

### Client
```bash
java ChatClientGUI
```

Or use the console client:
```bash
java ChatClient
```

### File encryption CLI
```bash
java FileCryptoCLI encrypt <file> <key>
java FileCryptoCLI decrypt <file> <key>
```

(if you want to encrypt/decrypt files)

## Security Notes
- Keys never leave the client.
- The server cannot read messages, voice, or files.
- Different keys on the same channel are supported, only matching keys decrypt the content.
- Voice messages and files use the full 1,000,000 PBKDF2 iterations.

## License

[MIT](LICENSE)