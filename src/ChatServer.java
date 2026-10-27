import java.io.*;
import java.net.*;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dumb relay server.
 * Text/control over TCP, audio over UDP.
 * Server never sees keys or plaintext.
 */
public class ChatServer {
    private final int port;
    private final Map<String, Set<PrintWriter>> channels = new ConcurrentHashMap<>();
    private final Map<Socket, String> clientChannels = new ConcurrentHashMap<>();
    // TCP socket -> UDP address the client is listening on
    private final Map<Socket, InetSocketAddress> clientUdp = new ConcurrentHashMap<>();
    private DatagramSocket udpSocket;

    public ChatServer(int port) {
        this.port = port;
    }

    public void start() throws IOException {
        int udpPort = port + 1;
        try {
            udpSocket = new DatagramSocket(udpPort);
            System.out.println("UDP listening on " + udpPort);
        } catch (Exception e) {
            System.out.println("FAILED to bind UDP port " + udpPort + ": " + e.getMessage());
            throw e;
        }
        new Thread(this::udpLoop, "udp-relay").start();

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("TCP listening on " + port);
            while (true) {
                Socket client = serverSocket.accept();
                System.out.println("Client connected: " + client.getRemoteSocketAddress());
                new Thread(new ClientHandler(client)).start();
            }
        }
    }

    private void udpLoop() {
        byte[] buf = new byte[65536];
        while (!udpSocket.isClosed()) {
            try {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                udpSocket.receive(packet);
                System.out.println("UDP recv " + packet.getAddress().getHostAddress() + ":" + packet.getPort() + " len=" + packet.getLength());

                InetSocketAddress from = new InetSocketAddress(packet.getAddress(), packet.getPort());
                if (from.getAddress().isLoopbackAddress()) {
                    from = new InetSocketAddress("127.0.0.1", from.getPort());
                }

                String channel = null;
                Socket senderSocket = null;
                for (var e : clientUdp.entrySet()) {
                    InetSocketAddress reg = e.getValue();
                    // match primarily by port (localhost safe); also accept address match
                    if (reg.getPort() == from.getPort()) {
                        senderSocket = e.getKey();
                        channel = clientChannels.get(senderSocket);
                        break;
                    }
                }
                if (channel == null) {
                    System.out.println("  no match for port " + from.getPort());
                    continue;
                }
                System.out.println("  relaying on channel " + channel);

                Set<PrintWriter> subs = channels.get(channel);
                if (subs == null) continue;

                byte[] data = new byte[packet.getLength()];
                System.arraycopy(packet.getData(), packet.getOffset(), data, 0, packet.getLength());
                int sent = 0;
                for (var e : clientUdp.entrySet()) {
                    if (e.getKey() == senderSocket) continue;
                    if (!channel.equals(clientChannels.get(e.getKey()))) continue;
                    DatagramPacket out = new DatagramPacket(data, data.length, e.getValue());
                    udpSocket.send(out);
                    sent++;
                }
                System.out.println("  forwarded to " + sent + " client(s)");
            } catch (IOException ex) {
                System.out.println("UDP error: " + ex.getMessage());
            }
        }
    }

    private class ClientHandler implements Runnable {
        private final Socket socket;
        private PrintWriter out;

        ClientHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                 PrintWriter writer = new PrintWriter(socket.getOutputStream(), true)) {
                this.out = writer;
                String line;
                while ((line = in.readLine()) != null) {
                    handle(line);
                }
            } catch (IOException e) {
                // client disconnected
            } finally {
                cleanup();
            }
        }

        private void handle(String line) {
            if (line.startsWith("JOIN ")) {
                String channel = line.substring(5).trim();
                if (channel.isEmpty()) return;
                leaveCurrent();
                Set<PrintWriter> set = channels.computeIfAbsent(channel, k -> Collections.newSetFromMap(new ConcurrentHashMap<>()));
                set.add(out);
                clientChannels.put(socket, channel);
                int count = set.size();
                System.out.println(socket.getRemoteSocketAddress() + " joined " + channel + " (" + count + ")");
                // notify everyone in the channel (including the joiner)
                String info = "INFO JOIN " + count;
                for (PrintWriter w : set) {
                    w.println(info);
                }
            } else if (line.startsWith("UDP ")) {
                try {
                    int udpPort = Integer.parseInt(line.substring(4).trim());
                    InetAddress addr = socket.getInetAddress();
                    // normalize localhost
                    if (addr.isLoopbackAddress()) {
                        addr = InetAddress.getByName("127.0.0.1");
                    }
                    clientUdp.put(socket, new InetSocketAddress(addr, udpPort));
                    System.out.println("Registered UDP " + addr.getHostAddress() + ":" + udpPort);
                } catch (Exception ignored) {}
            } else if (line.startsWith("MSG ") || line.startsWith("VOICE ") || line.startsWith("FILE ")) {
                String prefix = line.startsWith("MSG ") ? "MSG " :
                                line.startsWith("VOICE ") ? "VOICE " : "FILE ";
                int space = line.indexOf(' ', prefix.length());
                if (space < 0) return;
                String channel = line.substring(prefix.length(), space).trim();
                String payload = line.substring(space + 1).trim();
                if (channel.isEmpty() || payload.isEmpty()) return;

                Set<PrintWriter> subscribers = channels.get(channel);
                if (subscribers != null) {
                    String relay = prefix + channel + " " + payload;
                    for (PrintWriter w : subscribers) {
                        w.println(relay);
                    }
                }
            } else if (line.equals("LEAVE")) {
                leaveCurrent();
            }
        }

        private void leaveCurrent() {
            String current = clientChannels.remove(socket);
            if (current != null) {
                Set<PrintWriter> set = channels.get(current);
                if (set != null) {
                    set.remove(out);
                    int count = set.size();
                    if (set.isEmpty()) {
                        channels.remove(current);
                    } else {
                        // notify remaining users
                        String info = "INFO LEAVE " + count;
                        for (PrintWriter w : set) {
                            w.println(info);
                        }
                    }
                    System.out.println(socket.getRemoteSocketAddress() + " left " + current + " (" + count + " remaining)");
                }
            }
        }

        private void cleanup() {
            leaveCurrent();
            clientUdp.remove(socket);
            try {
                socket.close();
            } catch (IOException ignored) {}
            System.out.println("Client disconnected: " + socket.getRemoteSocketAddress());
        }
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 5555;
        new ChatServer(port).start();
    }
}
