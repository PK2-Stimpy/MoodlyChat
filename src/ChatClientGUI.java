import javax.sound.sampled.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.*;
import java.net.*;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Simple old-looking Swing GUI client with basic push-to-talk voice.
 * Text uses 1M PBKDF2 iterations; audio uses 100 iterations for lower latency (works really bad...).
 * Key and username stay local; failed decrypts are silently ignored.
 */
public class ChatClientGUI extends JFrame {
    private static final AudioFormat VOICE_FORMAT = new AudioFormat(16000f, 16, 1, true, false);
    private static final int VOICE_BUFFER = 1024;

    private String host = "localhost";
    private int port = 5555;
    private String username = "anonymous";
    private String channel = null;
    private String key = null;

    private Socket socket;
    private PrintWriter out;
    private volatile boolean running = false;

    // Audio (UDP)
    private DatagramSocket udpSocket;
    private volatile boolean audioEnabled = false;
    private volatile boolean talking = false;
    private TargetDataLine micLine;
    private SourceDataLine speakerLine;
    private Thread captureThread;
    private Thread udpReceiveThread;
    private javax.crypto.SecretKey audioKey;
    private byte[] audioSalt;
    private final LinkedBlockingQueue<byte[]> audioQueue = new LinkedBlockingQueue<>(16);
    private volatile boolean playbackRunning = false;
    private Thread playbackThread;
    private int lastRate = -1, lastChannels = -1, lastBits = -1;

    // UI
    private final JTextField hostField = new JTextField("node7.furryporn.fun", 12);
    private final JTextField portField = new JTextField("5555", 5);
    private final JButton connectBtn = new JButton("Connect");
    private final JTextField nickField = new JTextField("anonymous", 12);
    private final JButton nickBtn = new JButton("Set Nick");

    private final JTextField channelField = new JTextField(12);
    private final JTextField keyField = new JTextField(12);
    private final JButton joinBtn = new JButton("Join");
    private final JButton leaveBtn = new JButton("Leave");

    private final JCheckBox audioCheck = new JCheckBox("Enable voice");
    private final JComboBox<String> deviceCombo = new JComboBox<>();
    private final JButton pttButton = new JButton("Push to Talk");

    private final JTextArea chatArea = new JTextArea();
    private final JTextField messageField = new JTextField();
    private final JButton sendBtn = new JButton("Send");
    private final JButton voiceMsgBtn = new JButton("Record voice");
    private final JButton fileBtn = new JButton("Send file");
    private final JTextField maxRecvField = new JTextField("10", 4);
    private final JCheckBox autoplayCheck = new JCheckBox("Autoplay images/videos", true);
    private final JCheckBox voiceMsgCheck = new JCheckBox("Allow voice messages", true);

    private final JLabel statusLabel = new JLabel("Disconnected");

    public ChatClientGUI() {
        super("Moodly Chat v1.0");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(560, 560);
        setLocationRelativeTo(null);

        try {
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        } catch (Exception ignored) {}

        populateDevices();
        buildUI();
        wireEvents();

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                stopAudio();
                disconnect();
            }
        });
    }

    private void populateDevices() {
        deviceCombo.removeAllItems();
        Mixer.Info[] mixers = AudioSystem.getMixerInfo();
        for (Mixer.Info info : mixers) {
            Mixer mixer = AudioSystem.getMixer(info);
            // show any mixer that has a target data line (we'll negotiate format later)
            if (mixer.getTargetLineInfo().length > 0) {
                deviceCombo.addItem(info.getName());
            }
        }
        if (deviceCombo.getItemCount() == 0) {
            deviceCombo.addItem("(no input devices)");
        }
    }

    private AudioFormat findSupportedFormat(Mixer mixer) {
        // prefer low-rate mono for voice stability
        AudioFormat[] candidates = {
            new AudioFormat(8000f, 16, 1, true, false),
            new AudioFormat(16000f, 16, 1, true, false),
            new AudioFormat(22050f, 16, 1, true, false),
            new AudioFormat(11025f, 16, 1, true, false),
            new AudioFormat(44100f, 16, 1, true, false),
            new AudioFormat(48000f, 16, 1, true, false)
        };
        for (AudioFormat fmt : candidates) {
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, fmt);
            if (mixer.isLineSupported(info)) {
                return fmt;
            }
        }
        Line.Info[] infos = mixer.getTargetLineInfo();
        if (infos.length > 0 && infos[0] instanceof DataLine.Info) {
            AudioFormat[] formats = ((DataLine.Info) infos[0]).getFormats();
            if (formats.length > 0) return formats[0];
        }
        return null;
    }

    private void buildUI() {
        JPanel root = new JPanel(new BorderLayout(6, 6));
        root.setBorder(new EmptyBorder(8, 8, 8, 8));
        root.setBackground(new Color(192, 192, 192));

        // Connection
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        top.setBackground(new Color(192, 192, 192));
        top.setBorder(new TitledBorder("Connection"));
        top.add(new JLabel("Host:"));
        top.add(hostField);
        top.add(new JLabel("Port:"));
        top.add(portField);
        top.add(connectBtn);
        top.add(new JLabel("Nick:"));
        top.add(nickField);
        top.add(nickBtn);

        // Channel
        JPanel channelPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        channelPanel.setBackground(new Color(192, 192, 192));
        channelPanel.setBorder(new TitledBorder("Channel (key stays local)"));
        channelPanel.add(new JLabel("Channel:"));
        channelPanel.add(channelField);
        channelPanel.add(new JLabel("Key:"));
        channelPanel.add(keyField);
        channelPanel.add(joinBtn);
        channelPanel.add(leaveBtn);

        // Voice
        JPanel voicePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        voicePanel.setBackground(new Color(192, 192, 192));
        voicePanel.setBorder(new TitledBorder("Voice (100 PBKDF2 rounds)"));
        voicePanel.add(audioCheck);
        voicePanel.add(new JLabel("Mic:"));
        voicePanel.add(deviceCombo);
        pttButton.setEnabled(false);
        voicePanel.add(pttButton);

        // Settings
        JPanel settingsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        settingsPanel.setBackground(new Color(192, 192, 192));
        settingsPanel.setBorder(new TitledBorder("File settings"));
        settingsPanel.add(new JLabel("Max receive (MB):"));
        settingsPanel.add(maxRecvField);
        settingsPanel.add(autoplayCheck);
        settingsPanel.add(voiceMsgCheck);

        JPanel north = new JPanel(new BorderLayout());
        north.setBackground(new Color(192, 192, 192));
        north.add(top, BorderLayout.NORTH);
        north.add(channelPanel, BorderLayout.CENTER);
        JPanel southNorth = new JPanel(new BorderLayout());
        southNorth.setBackground(new Color(192, 192, 192));
        southNorth.add(voicePanel, BorderLayout.NORTH);
        southNorth.add(settingsPanel, BorderLayout.SOUTH);
        north.add(southNorth, BorderLayout.SOUTH);

        // Chat
        chatArea.setEditable(false);
        chatArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        chatArea.setBackground(new Color(255, 255, 224));
        chatArea.setLineWrap(true);
        chatArea.setWrapStyleWord(true);
        JScrollPane scroll = new JScrollPane(chatArea);
        scroll.setBorder(new TitledBorder("Messages"));

        // Bottom
        JPanel bottom = new JPanel(new BorderLayout(4, 4));
        bottom.setBackground(new Color(192, 192, 192));
        bottom.setBorder(new EmptyBorder(4, 0, 0, 0));
        bottom.add(messageField, BorderLayout.CENTER);
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        btnPanel.setBackground(new Color(192, 192, 192));
        btnPanel.add(voiceMsgBtn);
        btnPanel.add(fileBtn);
        btnPanel.add(sendBtn);
        bottom.add(btnPanel, BorderLayout.EAST);

        statusLabel.setBorder(new EmptyBorder(2, 4, 2, 4));
        statusLabel.setForeground(Color.DARK_GRAY);

        root.add(north, BorderLayout.NORTH);
        root.add(scroll, BorderLayout.CENTER);
        root.add(bottom, BorderLayout.SOUTH);

        JPanel outer = new JPanel(new BorderLayout());
        outer.add(root, BorderLayout.CENTER);
        outer.add(statusLabel, BorderLayout.SOUTH);
        setContentPane(outer);
    }

    private void wireEvents() {
        connectBtn.addActionListener(e -> doConnect());
        nickBtn.addActionListener(e -> {
            String n = nickField.getText().trim();
            if (n.isEmpty()) return;
            if (n.length() > 24) {
                appendSystem("Nickname too long (max 24 characters)");
                return;
            }
            username = n;
            appendSystem("Username set to: " + username + " (local only)");
        });
        joinBtn.addActionListener(e -> doJoin());
        leaveBtn.addActionListener(e -> doLeave());
        sendBtn.addActionListener(e -> sendMessage());
        messageField.addActionListener(e -> sendMessage());
        voiceMsgBtn.addActionListener(e -> toggleVoiceMessage());
        fileBtn.addActionListener(e -> sendFile());

        audioCheck.addActionListener(e -> {
            audioEnabled = audioCheck.isSelected();
            pttButton.setEnabled(audioEnabled && channel != null && key != null);
            if (!audioEnabled) stopAudio();
            appendSystem(audioEnabled ? "Voice enabled" : "Voice disabled");
        });

        pttButton.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (audioEnabled && channel != null && key != null && out != null) {
                    startTalking();
                }
            }
            @Override
            public void mouseReleased(MouseEvent e) {
                stopTalking();
            }
            @Override
            public void mouseExited(MouseEvent e) {
                // if the mouse leaves the button while held, still stop
                if (talking) stopTalking();
            }
        });
    }

    private void doConnect() {
        try {
            host = hostField.getText().trim();
            port = Integer.parseInt(portField.getText().trim());
            connect();
            statusLabel.setText("Connected to " + host + ":" + port);
            appendSystem("Connected to " + host + ":" + port);
        } catch (Exception ex) {
            appendSystem("Connect failed: " + ex.getMessage());
            statusLabel.setText("Disconnected");
        }
    }

    private void doJoin() {
        String ch = channelField.getText().trim();
        String k = keyField.getText().trim();
        if (ch.isEmpty() || k.isEmpty()) {
            appendSystem("Channel and key required");
            return;
        }
        channel = ch;
        key = k;
        try {
            // deterministic salt from the key so both sides derive the same audio key
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            audioSalt = md.digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            audioSalt = java.util.Arrays.copyOf(audioSalt, 16);
            audioKey = SecureEncryption.deriveAudioKey(key, audioSalt);
        } catch (Exception e) {
            appendSystem("Key derive failed: " + e.getMessage());
        }
        if (out != null) {
            out.println("JOIN " + channel);
            if (udpSocket != null) {
                out.println("UDP " + udpSocket.getLocalPort());
            }
        }
        appendSystem("Joined channel '" + channel + "'");
        statusLabel.setText("Connected | channel: " + channel);
        pttButton.setEnabled(audioEnabled);
    }

    private void doLeave() {
        if (out != null && channel != null) {
            out.println("LEAVE");
        }
        channel = null;
        key = null;
        // stop audio off the EDT so the UI doesn't freeze
        new Thread(this::stopAudio, "stop-audio").start();
        appendSystem("Left channel");
        statusLabel.setText(out != null ? "Connected" : "Disconnected");
        pttButton.setEnabled(false);
        pttButton.setText("Push to Talk");
    }

    private void connect() throws IOException {
        disconnect();
        socket = new Socket(host, port);
        out = new PrintWriter(socket.getOutputStream(), true);
        running = true;
        new Thread(this::listen, "chat-listener").start();

        // UDP for audio
        udpSocket = new DatagramSocket();
        out.println("UDP " + udpSocket.getLocalPort());
        startUdpReceive();

        if (channel != null) {
            out.println("JOIN " + channel);
        }
    }

    private void disconnect() {
        running = false;
        stopAudio();
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {}
        try {
            if (udpSocket != null) udpSocket.close();
        } catch (Exception ignored) {}
        socket = null;
        out = null;
        udpSocket = null;
    }

    private void listen() {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
            String line;
            while (running && (line = in.readLine()) != null) {
                if (line.startsWith("MSG ")) {
                    handleIncomingText(line);
                } else if (line.startsWith("VOICE ")) {
                    handleIncomingVoice(line);
                } else if (line.startsWith("FILE ")) {
                    handleIncomingFile(line);
                } else if (line.startsWith("INFO ")) {
                    handleInfo(line);
                }
            }
        } catch (IOException e) {
            if (running) {
                SwingUtilities.invokeLater(() -> {
                    appendSystem("Disconnected from server");
                    statusLabel.setText("Disconnected");
                });
            }
        }
    }

    private void handleIncomingText(String line) {
        int firstSpace = line.indexOf(' ');
        int secondSpace = line.indexOf(' ', firstSpace + 1);
        if (secondSpace < 0) return;

        String msgChannel = line.substring(firstSpace + 1, secondSpace);
        String encrypted = line.substring(secondSpace + 1);

        if (channel == null || !channel.equals(msgChannel) || key == null) return;

        // show temporary "someone is typing" while decrypt
        SwingUtilities.invokeLater(() -> {
            chatArea.append("* someone is typing...\n");
            chatArea.setCaretPosition(chatArea.getDocument().getLength());
        });

        try {
            String decrypted = SecureEncryption.decrypt(encrypted, key);
            int sep = decrypted.indexOf('|');
            if (sep < 0) {
                removeLastSystemLine();
                return;
            }
            String sender = decrypted.substring(0, sep);
            String message = decrypted.substring(sep + 1);
            SwingUtilities.invokeLater(() -> {
                removeLastSystemLine();
                chatArea.append("[" + sender + "] " + message + "\n");
                chatArea.setCaretPosition(chatArea.getDocument().getLength());
            });
        } catch (Exception ignored) {
            removeLastSystemLine();
        }
    }

    private void removeLastSystemLine() {
        SwingUtilities.invokeLater(() -> {
            try {
                String text = chatArea.getText();
                int last = text.lastIndexOf("* someone is typing...\n");
                if (last >= 0) {
                    chatArea.replaceRange("", last, last + "* someone is typing...\n".length());
                }
            } catch (Exception ignored) {}
        });
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
            appendSystem("User joined (" + count + " users)");
        } else if ("LEAVE".equals(type)) {
            appendSystem("User left (" + count + " users)");
        }
    }

    private void ensureSpeaker(int sampleRate, int channels, int sampleSize) {
        if (speakerLine != null && speakerLine.isOpen()
                && lastRate == sampleRate && lastChannels == channels && lastBits == sampleSize) {
            return;
        }
        if (speakerLine != null) {
            try { speakerLine.drain(); speakerLine.stop(); speakerLine.close(); } catch (Exception ignored) {}
            speakerLine = null;
        }
        try {
            AudioFormat fmt = new AudioFormat(sampleRate, sampleSize, channels, true, false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, fmt);
            speakerLine = (SourceDataLine) AudioSystem.getLine(info);
            speakerLine.open(fmt, Math.max(sampleRate, 8000));
            speakerLine.start();
            lastRate = sampleRate;
            lastChannels = channels;
            lastBits = sampleSize;
            appendSystem("speaker opened " + sampleRate + " Hz");
        } catch (Exception e) {
            appendSystem("Speaker open failed: " + e.getMessage());
            speakerLine = null;
        }
    }

    private void sendMessage() {
        String text = messageField.getText().trim();
        if (text.isEmpty()) return;
        if (out == null) {
            appendSystem("Not connected");
            return;
        }
        if (channel == null || key == null) {
            appendSystem("Not in a channel");
            return;
        }

        sendBtn.setEnabled(false);
        sendBtn.setText("Sending...");
        messageField.setEnabled(false);

        final String toSend = text;
        new Thread(() -> {
            try {
                String payload = username + "|" + toSend;
                String encrypted = SecureEncryption.encrypt(payload, key);
                out.println("MSG " + channel + " " + encrypted);
                SwingUtilities.invokeLater(() -> {
                    messageField.setText("");
                    appendSystem("Message sent");
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> appendSystem("Send failed: " + e.getMessage()));
            } finally {
                SwingUtilities.invokeLater(() -> {
                    sendBtn.setEnabled(true);
                    sendBtn.setText("Send");
                    messageField.setEnabled(true);
                    messageField.requestFocus();
                });
            }
        }, "send-thread").start();
    }

    private void startSpeaker() {
        // speaker is opened lazily with the same format as the mic
    }

    private void ensureSpeaker(AudioFormat format) {
        // unused now
    }

    private void startUdpReceive() {
        if (udpReceiveThread != null && udpReceiveThread.isAlive()) return;
        udpReceiveThread = new Thread(() -> {
            byte[] buf = new byte[65536];
            ensureSpeaker(16000, 1, 16);
            startPlaybackIfNeeded();
            while (running && udpSocket != null && !udpSocket.isClosed()) {
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    udpSocket.receive(p);
                    if (!audioEnabled || audioKey == null) continue;

                    byte[] data = new byte[p.getLength()];
                    System.arraycopy(p.getData(), p.getOffset(), data, 0, p.getLength());
                    byte[] pcm;
                    try {
                        pcm = SecureEncryption.decryptWithKey(data, audioKey);
                    } catch (Exception ex) {
                        continue;
                    }
                    if (pcm.length == 0) continue;
                    // keep queue short
                    while (audioQueue.size() > 8) audioQueue.poll();
                    audioQueue.offer(pcm);
                } catch (Exception ignored) {}
            }
        }, "udp-receive");
        udpReceiveThread.setDaemon(true);
        udpReceiveThread.start();
    }

    private void startPlaybackIfNeeded() {
        if (playbackRunning) return;
        playbackRunning = true;
        playbackThread = new Thread(() -> {
            while (playbackRunning) {
                try {
                    byte[] pcm = audioQueue.poll(50, TimeUnit.MILLISECONDS);
                    if (pcm != null && speakerLine != null && speakerLine.isOpen()) {
                        speakerLine.write(pcm, 0, pcm.length);
                    }
                } catch (Exception ignored) {}
            }
        }, "audio-playback");
        playbackThread.setDaemon(true);
        playbackThread.start();
    }

    private void startTalking() {
        if (talking || udpSocket == null || channel == null || audioKey == null) return;
        talking = true;
        pttButton.setText("Talking...");
        appendSystem("Opening mic...");
        captureThread = new Thread(() -> {
            try {
                DataLine.Info info = new DataLine.Info(TargetDataLine.class, VOICE_FORMAT);
                if (!AudioSystem.isLineSupported(info)) {
                    SwingUtilities.invokeLater(() -> appendSystem("16 kHz mono not supported on this mic"));
                    talking = false;
                    return;
                }
                micLine = (TargetDataLine) AudioSystem.getLine(info);
                micLine.open(VOICE_FORMAT);
                micLine.start();
                audioQueue.clear();
                SwingUtilities.invokeLater(() -> appendSystem("Mic ready (16000 Hz)"));

                byte[] buf = new byte[VOICE_BUFFER];
                InetAddress serverAddr = InetAddress.getByName(host);
                while (talking && running && udpSocket != null) {
                    int n = micLine.read(buf, 0, buf.length);
                    if (n <= 0) continue;
                    byte[] frame = new byte[n];
                    System.arraycopy(buf, 0, frame, 0, n);
                    byte[] encrypted = SecureEncryption.encryptWithKey(frame, audioKey);
                    DatagramPacket packet = new DatagramPacket(encrypted, encrypted.length, serverAddr, port + 1);
                    udpSocket.send(packet);
                }
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> appendSystem("Mic error: " + e.getMessage()));
                talking = false;
            } finally {
                if (micLine != null) {
                    try { micLine.stop(); micLine.close(); } catch (Exception ignored) {}
                    micLine = null;
                }
                SwingUtilities.invokeLater(() -> pttButton.setText("Push to Talk"));
            }
        }, "mic-capture");
        captureThread.setDaemon(true);
        captureThread.start();
    }

    private void stopTalking() {
        talking = false;
        pttButton.setText("Push to Talk");
    }

    private void stopAudio() {
        talking = false;
        playbackRunning = false;
        audioQueue.clear();
        if (micLine != null) {
            try { micLine.stop(); } catch (Exception ignored) {}
            try { micLine.close(); } catch (Exception ignored) {}
            micLine = null;
        }
        if (speakerLine != null) {
            try { speakerLine.stop(); } catch (Exception ignored) {}
            try { speakerLine.close(); } catch (Exception ignored) {}
            speakerLine = null;
        }
        lastRate = lastChannels = lastBits = -1;
    }

    private volatile boolean recordingVoice = false;
    private ByteArrayOutputStream voiceBuffer;
    private Thread voiceRecordThread;

    private void toggleVoiceMessage() {
        if (channel == null || key == null || out == null) {
            appendSystem("Join a channel first");
            return;
        }
        if (!recordingVoice) {
            startVoiceMessage();
        } else {
            stopVoiceMessage();
        }
    }

    private void startVoiceMessage() {
        recordingVoice = true;
        voiceMsgBtn.setText("Stop & send");
        voiceBuffer = new ByteArrayOutputStream();
        appendSystem("Recording voice message...");
        voiceRecordThread = new Thread(() -> {
            try {
                DataLine.Info info = new DataLine.Info(TargetDataLine.class, VOICE_FORMAT);
                if (!AudioSystem.isLineSupported(info)) {
                    SwingUtilities.invokeLater(() -> appendSystem("Mic format not supported"));
                    recordingVoice = false;
                    return;
                }
                TargetDataLine line = (TargetDataLine) AudioSystem.getLine(info);
                line.open(VOICE_FORMAT);
                line.start();
                byte[] buf = new byte[1024];
                while (recordingVoice) {
                    int n = line.read(buf, 0, buf.length);
                    if (n > 0) voiceBuffer.write(buf, 0, n);
                }
                line.stop();
                line.close();
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> appendSystem("Record error: " + e.getMessage()));
            }
        }, "voice-msg");
        voiceRecordThread.setDaemon(true);
        voiceRecordThread.start();
    }

    private void stopVoiceMessage() {
        recordingVoice = false;
        voiceMsgBtn.setText("Record voice");
        try {
            if (voiceRecordThread != null) voiceRecordThread.join(500);
        } catch (Exception ignored) {}
        byte[] pcm = voiceBuffer.toByteArray();
        if (pcm.length < 100) {
            appendSystem("Recording too short");
            return;
        }
        appendSystem("Preparing voice message...");
        setStatus("Sending voice 0%");
        new Thread(() -> {
            try {
                setStatus("Sending voice 10% (encoding)");
                String b64 = Base64.getEncoder().encodeToString(pcm);
                setStatus("Sending voice 30% (deriving key)");
                String payload = username + "|VOICE|" + b64;
                String encrypted = SecureEncryption.encrypt(payload, key);
                setStatus("Sending voice 90% (sending)");
                out.println("VOICE " + channel + " " + encrypted);
                SwingUtilities.invokeLater(() -> {
                    appendSystem("Voice message sent");
                    setStatus("Connected | channel: " + channel);
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    appendSystem("Voice send failed: " + e.getMessage());
                    setStatus("Connected | channel: " + channel);
                });
            }
        }, "voice-send").start();
    }

    private void setStatus(String text) {
        SwingUtilities.invokeLater(() -> statusLabel.setText(text));
    }

    private void sendFile() {
        if (channel == null || key == null || out == null) {
            appendSystem("Join a channel first");
            return;
        }
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File file = chooser.getSelectedFile();
        if (file.length() > 50_000_000) {
            appendSystem("File too big (max 50 MB)");
            return;
        }
        appendSystem("Preparing file...");
        setStatus("Sending file 0%");
        new Thread(() -> {
            try {
                setStatus("Sending file 15% (reading)");
                byte[] data = java.nio.file.Files.readAllBytes(file.toPath());
                setStatus("Sending file 35% (encoding)");
                String b64 = Base64.getEncoder().encodeToString(data);
                String payload = username + "|FILE|" + file.getName() + "|" + b64;
                setStatus("Sending file 50% (deriving key)");
                String encrypted = SecureEncryption.encrypt(payload, key);
                setStatus("Sending file 90% (sending)");
                out.println("FILE " + channel + " " + encrypted);
                SwingUtilities.invokeLater(() -> {
                    appendSystem("File sent: " + file.getName());
                    setStatus("Connected | channel: " + channel);
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    appendSystem("File send failed: " + e.getMessage());
                    setStatus("Connected | channel: " + channel);
                });
            }
        }, "file-send").start();
    }

    private void handleIncomingVoice(String line) {
        int first = line.indexOf(' ');
        int second = line.indexOf(' ', first + 1);
        if (second < 0) return;
        String msgChannel = line.substring(first + 1, second);
        String encrypted = line.substring(second + 1);
        if (channel == null || !channel.equals(msgChannel) || key == null) return;
        if (!voiceMsgCheck.isSelected()) {
            SwingUtilities.invokeLater(() -> appendSystem("Voice message ignored (disabled)"));
            return;
        }
        new Thread(() -> {
            try {
                setStatus("Receiving voice...");
                String decrypted = SecureEncryption.decrypt(encrypted, key);
                String[] parts = decrypted.split("\\|", 3);
                if (parts.length < 3 || !"VOICE".equals(parts[1])) return;
                String sender = parts[0];
                byte[] pcm = Base64.getDecoder().decode(parts[2]);
                SwingUtilities.invokeLater(() -> {
                    appendSystem("Voice message from " + sender);
                    setStatus("Connected | channel: " + channel);
                });
                playPcm(pcm);
            } catch (Exception ignored) {}
        }, "voice-play").start();
    }

    private void handleIncomingFile(String line) {
        int first = line.indexOf(' ');
        int second = line.indexOf(' ', first + 1);
        if (second < 0) return;
        String msgChannel = line.substring(first + 1, second);
        String encrypted = line.substring(second + 1);
        if (channel == null || !channel.equals(msgChannel) || key == null) return;
        new Thread(() -> {
            try {
                setStatus("Receiving file...");
                String decrypted = SecureEncryption.decrypt(encrypted, key);
                String[] parts = decrypted.split("\\|", 4);
                if (parts.length < 4 || !"FILE".equals(parts[1])) return;
                String sender = parts[0];
                String name = parts[2];
                byte[] data = Base64.getDecoder().decode(parts[3]);
                SwingUtilities.invokeLater(() -> {
                    setStatus("Connected | channel: " + channel);
                    offerFile(sender, name, data);
                });
            } catch (Exception ignored) {}
        }, "file-recv").start();
    }

    private void offerFile(String sender, String name, byte[] data) {
        int maxMb = 10;
        try {
            maxMb = Integer.parseInt(maxRecvField.getText().trim());
        } catch (Exception ignored) {}
        long maxBytes = maxMb * 1024L * 1024L;
        if (data.length > maxBytes) {
            appendSystem("Rejected " + name + " (over your " + maxMb + " MB limit)");
            return;
        }

        appendSystem("File from " + sender + ": " + name + " (" + (data.length / 1024) + " KB)");
        int choice = JOptionPane.showConfirmDialog(
                this,
                sender + " sent a file:\n" + name + " (" + (data.length / 1024) + " KB)\n\nAccept it?",
                "Incoming file",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE
        );
        if (choice != JOptionPane.YES_OPTION) {
            appendSystem("Declined file: " + name);
            return;
        }

        try {
            File dir = new File("downloads");
            if (!dir.exists()) dir.mkdirs();
            File dest = new File(dir, name);
            // avoid overwrite
            int n = 1;
            String base = name;
            String ext = "";
            int dot = name.lastIndexOf('.');
            if (dot > 0) {
                base = name.substring(0, dot);
                ext = name.substring(dot);
            }
            while (dest.exists()) {
                dest = new File(dir, base + "_" + n + ext);
                n++;
            }
            java.nio.file.Files.write(dest.toPath(), data);
            appendSystem("Saved to " + dest.getPath());

            String lower = name.toLowerCase();
            boolean isMedia = lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                    || lower.endsWith(".gif") || lower.endsWith(".bmp")
                    || lower.endsWith(".mp4") || lower.endsWith(".webm") || lower.endsWith(".mkv");
            if (autoplayCheck.isSelected() && isMedia) {
                if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                        || lower.endsWith(".gif") || lower.endsWith(".bmp")) {
                    showImage(name, data);
                } else {
                    // try to open video with system player
                    try {
                        if (java.awt.Desktop.isDesktopSupported()) {
                            java.awt.Desktop.getDesktop().open(dest);
                        }
                    } catch (Exception ex) {
                        appendSystem("Could not autoplay video: " + ex.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            appendSystem("Save failed: " + e.getMessage());
        }
    }

    private void playPcm(byte[] pcm) {
        try {
            ensureSpeaker(16000, 1, 16);
            if (speakerLine != null && speakerLine.isOpen()) {
                speakerLine.write(pcm, 0, pcm.length);
                speakerLine.drain();
            }
        } catch (Exception e) {
            appendSystem("Playback failed: " + e.getMessage());
        }
    }

    private void showImage(String name, byte[] data) {
        try {
            ImageIcon icon = new ImageIcon(data);
            JLabel label = new JLabel(icon);
            JScrollPane sp = new JScrollPane(label);
            sp.setPreferredSize(new Dimension(Math.min(600, icon.getIconWidth() + 20),
                                              Math.min(500, icon.getIconHeight() + 20)));
            JOptionPane.showMessageDialog(this, sp, name, JOptionPane.PLAIN_MESSAGE);
        } catch (Exception e) {
            appendSystem("Could not display image");
        }
    }

    private void appendSystem(String msg) {
        SwingUtilities.invokeLater(() -> {
            chatArea.append("* " + msg + "\n");
            chatArea.setCaretPosition(chatArea.getDocument().getLength());
        });
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            ChatClientGUI gui = new ChatClientGUI();
            gui.setVisible(true);
        });
    }
}
