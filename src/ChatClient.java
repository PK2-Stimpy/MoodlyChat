import java.io.*;
import java.net.Socket;
import java.util.Scanner;

/**
 * Console chat client.
 * - Username and channel key are stored only locally.
 * - All chat content is encrypted before leaving the client.
 * - Incoming messages are decrypted with the local key; failures are silently ignored.
 * - Multiple independent key groups can share the same channel name.
 */
public class ChatClient {
    private String host = "localhost";
    private int port = 5555;
    private String username = "anonymous";
    private String channel = null;
    private String key = null;

    private Socket socket;
    private PrintWriter out;
    private volatile boolean running = false;

    public void start() {
        Scanner scanner = new Scanner(System.in);
        System.out.println("=== Secure Chat Client ===");
        System.out.println("Commands:");
        System.out.println("  /connect <host> <port>");
        System.out.println("  /nick <username>");
        System.out.println("  /join <channel> <key>     (creates or joins; key stays local)");
        System.out.println("  /leave");
        System.out.println("  /quit");
        System.out.println("  anything else = send message (must be joined)");
        System.out.println();

        while (true) {
            System.out.print("> ");
            String line = scanner.nextLine().trim();
            if (line.isEmpty()) continue;

            if (line.startsWith("/")) {
                handleCommand(line);
                if (line.equals("/quit")) break;
            } else {
                sendMessage(line);
            }
        }
        disconnect();
        scanner.close();
    }

    private void handleCommand(String line) {
        String[] parts = line.split("\\s+", 3);
        String cmd = parts[0].toLowerCase();

        try {
            switch (cmd) {
                case "/connect" -> {
                    if (parts.length < 3) {
                        System.out.println("Usage: /connect <host> <port>");
                        return;
                    }
                    host = parts[1];
                    port = Integer.parseInt(parts[2]);
                    connect();
                }
                case "/nick" -> {
                    if (parts.length < 2) {
                        System.out.println("Usage: /nick <username>");
                        return;
                    }
                    String n = parts[1];
                    if (n.length() > 24) {
                        System.out.println("Nickname too long (max 24 characters)");
                        return;
                    }
                    username = n;
                    System.out.println("Username set to: " + username + " (only stored locally)");
                }
                case "/join" -> {
                    if (parts.length < 3) {
                        System.out.println("Usage: /join <channel> <key>");
                        return;
                    }
                    channel = parts[1];
                    key = parts[2];
                    if (out != null) {
                        out.println("JOIN " + channel);
                    }
                    System.out.println("Joined channel '" + channel + "' (key stays local)");
                }
                case "/leave" -> {
                    if (out != null && channel != null) {
                        out.println("LEAVE");
                    }
                    channel = null;
                    key = null;
                    System.out.println("Left channel");
                }
                case "/quit" -> {
                    System.out.println("Bye");
                }
                default -> System.out.println("Unknown command");
            }
        } catch (Exception e) {
            System.out.println("Error: " + e.getMessage());
        }
    }

    private void connect() throws IOException {
        disconnect();
        socket = new Socket(host, port);
        out = new PrintWriter(socket.getOutputStream(), true);
        running = true;
        new Thread(this::listen).start();
        System.out.println("Connected to " + host + ":" + port);
        if (channel != null) {
            out.println("JOIN " + channel);
        }
    }

    private void disconnect() {
        running = false;
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {}
        socket = null;
        out = null;
    }

    private void listen() {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
            String line;
            while (running && (line = in.readLine()) != null) {
                if (line.startsWith("MSG ")) {
                    handleIncoming(line);
                } else if (line.startsWith("INFO ")) {
                    handleInfo(line);
                }
            }
        } catch (IOException e) {
            if (running) {
                System.out.println("\nDisconnected from server");
            }
        }
    }

    private void handleIncoming(String line) {
        // MSG <channel> <base64>
        int firstSpace = line.indexOf(' ');
        int secondSpace = line.indexOf(' ', firstSpace + 1);
        if (secondSpace < 0) return;

        String msgChannel = line.substring(firstSpace + 1, secondSpace);
        String encrypted = line.substring(secondSpace + 1);

        // only try to decrypt if we are on this channel and have a key
        if (channel == null || !channel.equals(msgChannel) || key == null) {
            return;
        }

        try {
            String decrypted = SecureEncryption.decrypt(encrypted, key);
            int sep = decrypted.indexOf('|');
            if (sep < 0) return;
            String sender = decrypted.substring(0, sep);
            String message = decrypted.substring(sep + 1);
            System.out.println("\n[" + sender + "] " + message);
            System.out.print("> ");
        } catch (Exception ignored) {
            // wrong key or corrupted — silently ignore
        }
    }

    private void handleInfo(String line) {
        // INFO JOIN <count>   or   INFO LEAVE <count>
        String[] parts = line.split(" ");
        if (parts.length < 3) return;
        String type = parts[1];
        int count;
        try {
            count = Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
            return;
        }
        if ("JOIN".equals(type)) {
            System.out.println("\n* User joined (" + count + " users)");
        } else if ("LEAVE".equals(type)) {
            System.out.println("\n* User left (" + count + " users)");
        }
        System.out.print("> ");
    }

    private void sendMessage(String text) {
        if (out == null) {
            System.out.println("Not connected. Use /connect first.");
            return;
        }
        if (channel == null || key == null) {
            System.out.println("Not in a channel. Use /join <channel> <key> first.");
            return;
        }
        System.out.println("Sending...");
        try {
            String payload = username + "|" + text;
            String encrypted = SecureEncryption.encrypt(payload, key);
            out.println("MSG " + channel + " " + encrypted);
            System.out.println("Message sent");
        } catch (Exception e) {
            System.out.println("Encryption failed: " + e.getMessage());
        }
        System.out.print("> ");
    }

    public static void main(String[] args) {
        new ChatClient().start();
    }
}
