import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;

public class TypingRaceClientGUI extends JFrame {
    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    private String username;
    private int serverPort;

    private final JTextArea displayArea;
    private final JTextField inputField;
    private final JLabel timerLabel;
    private final JLabel wpmLabel;
    private final JButton connectButton;
    private final Timer raceTimer;
    private long startTime;
    private int charsTyped;

    public TypingRaceClientGUI() {
        setTitle("Typing Race Game");
        setSize(600, 400);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        displayArea = new JTextArea();
        displayArea.setEditable(false);
        displayArea.setFont(new Font("Monospaced", Font.PLAIN, 16));
        JScrollPane scrollPane = new JScrollPane(displayArea);

        JPanel statsPanel = new JPanel(new GridLayout(1, 2));
        timerLabel = new JLabel("Time: 0.00s", SwingConstants.CENTER);
        wpmLabel = new JLabel("WPM: 0", SwingConstants.CENTER);
        statsPanel.add(timerLabel);
        statsPanel.add(wpmLabel);

        inputField = new JTextField();
        inputField.setFont(new Font("Monospaced", Font.PLAIN, 16));
        inputField.setEnabled(false);
        inputField.addActionListener(e -> processInput());

        connectButton = new JButton("Connect to Server");
        connectButton.addActionListener(e -> connectToServer());

        add(scrollPane, BorderLayout.CENTER);
        add(statsPanel, BorderLayout.NORTH);
        add(inputField, BorderLayout.SOUTH);
        add(connectButton, BorderLayout.WEST);

        raceTimer = new Timer(100, evt -> updateTimer());
    }

    private void connectToServer() {
        String serverAddress = JOptionPane.showInputDialog(this, "Enter server address:", "localhost");
        if (serverAddress == null || serverAddress.trim().isEmpty()) {
            return;
        }

        String portStr = JOptionPane.showInputDialog(this, "Enter server port (leave blank for auto-detect):");

        connectButton.setEnabled(false);
        new Thread(() -> {
            try {
                if (portStr == null || portStr.trim().isEmpty()) {
                    autoConnect(serverAddress);
                } else {
                    manualConnect(serverAddress, Integer.parseInt(portStr));
                }
            } catch (NumberFormatException e) {
                SwingUtilities.invokeLater(() -> {
                    JOptionPane.showMessageDialog(this, "Invalid port number", "Error", JOptionPane.ERROR_MESSAGE);
                    connectButton.setEnabled(true);
                });
            }
        }).start();
    }

    private void autoConnect(String serverAddress) {
        int startPort = 5555;
        int maxAttempts = 10;

        for (int port = startPort; port < startPort + maxAttempts; port++) {
            try {
                attemptConnection(serverAddress, port);
                return;
            } catch (IOException e) {
                displayMessage("Failed to connect to port " + port + ": " + e.getMessage());
            }
        }

        SwingUtilities.invokeLater(() -> {
            JOptionPane.showMessageDialog(this,
                    "Failed to connect after trying ports " + startPort + "-" + (startPort + maxAttempts - 1),
                    "Connection Error", JOptionPane.ERROR_MESSAGE);
            connectButton.setEnabled(true);
        });
    }

    private void manualConnect(String serverAddress, int port) {
        try {
            attemptConnection(serverAddress, port);
        } catch (IOException e) {
            SwingUtilities.invokeLater(() -> {
                JOptionPane.showMessageDialog(this, "Failed to connect: " + e.getMessage(),
                        "Connection Error", JOptionPane.ERROR_MESSAGE);
                connectButton.setEnabled(true);
            });
        }
    }

    private void attemptConnection(String serverAddress, int port) throws IOException {
        displayMessage("Connecting to " + serverAddress + ":" + port + "...");
        socket = new Socket();
        socket.connect(new InetSocketAddress(serverAddress, port), 3000);
        out = new PrintWriter(socket.getOutputStream(), true);
        in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

        // ✅ Use instance variable directly
        this.username = JOptionPane.showInputDialog(this, "Enter your username:");
        if (this.username == null || this.username.trim().isEmpty()) {
            this.username = "Player" + (int) (Math.random() * 1000);
        }
        out.println(this.username);

        new Thread(this::receiveMessages).start();

        SwingUtilities.invokeLater(() -> {
            inputField.setEnabled(true);
            inputField.requestFocus();
            displayMessage("Connected to server as " + this.username); // ✅ this.username is OK
        });
    }

    private void receiveMessages() {
        try {
            String message;
            while ((message = in.readLine()) != null) {
                final String finalMessage = message; // ✅ wrap message in final variable
                if (message.startsWith("WELCOME|")) {
                    SwingUtilities.invokeLater(() -> displayMessage(finalMessage.substring(8)));
                } else if (message.startsWith("RACE_START|")) {
                    String sentence = finalMessage.substring(11);
                    startRace(sentence);
                } else if (message.startsWith("RACE_END|")) {
                    endRace(finalMessage.substring(9));
                } else if (message.startsWith("PORT|")) {
                    serverPort = Integer.parseInt(finalMessage.substring(5));
                } else {
                    displayMessage("[Server] " + finalMessage);
                }
            }
        } catch (IOException e) {
            if (!socket.isClosed()) {
                SwingUtilities.invokeLater(() -> displayMessage("Connection lost: " + e.getMessage()));
            }
        } finally {
            SwingUtilities.invokeLater(() -> {
                inputField.setEnabled(false);
                connectButton.setEnabled(true);
            });
            closeResources();
        }
    }

    private void startRace(String sentence) {
        SwingUtilities.invokeLater(() -> {
            displayArea.setText("TYPE THIS:\n\n" + sentence + "\n\n");
            inputField.setText("");
            inputField.setEnabled(true);
            inputField.requestFocus();

            startTime = System.currentTimeMillis();
            charsTyped = 0;
            raceTimer.start();
        });
    }

    private void endRace(String results) {
        SwingUtilities.invokeLater(() -> {
            raceTimer.stop();
            displayArea.append("\n\n" + results + "\nWaiting for next race...");
            inputField.setEnabled(false);
        });
    }

    private void updateTimer() {
        long elapsed = System.currentTimeMillis() - startTime;
        double seconds = elapsed / 1000.0;
        SwingUtilities.invokeLater(() -> timerLabel.setText(String.format("Time: %.2fs", seconds)));

        if (charsTyped > 0) {
            double minutes = seconds / 60.0;
            int wpm = (int) ((charsTyped / 5) / minutes);
            SwingUtilities.invokeLater(() -> wpmLabel.setText("WPM: " + wpm));
        }
    }

    private void processInput() {
        String typedText = inputField.getText();
        charsTyped += typedText.length();
        out.println(typedText);
        inputField.setText("");
    }

    private void displayMessage(String message) {
        SwingUtilities.invokeLater(() -> {
            displayArea.append(message + "\n");
            displayArea.setCaretPosition(displayArea.getDocument().getLength());
        });
    }

    private void closeResources() {
        try {
            if (out != null)
                out.close();
            if (in != null)
                in.close();
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException e) {
            System.err.println("Error closing resources: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            TypingRaceClientGUI client = new TypingRaceClientGUI();
            client.setVisible(true);
        });
    }
}
