import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class TypingRaceServer {
    private static final int DEFAULT_PORT = 5555;
    private static final int MAX_PORT_ATTEMPTS = 10;
    private static final String[] SENTENCES = {
        "The quick brown fox jumps over the lazy dog",
        "Programming is the art of telling another human what one wants the computer to do",
        "Java is to JavaScript what car is to carpet",
        "Clean code always looks like it was written by someone who cares",
        "First solve the problem then write the code"
    };
    
    private ServerSocket serverSocket;
    private ExecutorService pool;
    private List<ClientHandler> clients;
    private boolean raceInProgress;
    private String currentSentence;
    private long raceStartTime;
    private Map<String, Long> finishTimes;
    private int currentPort;

    public TypingRaceServer() {
        clients = new ArrayList<>();
        pool = Executors.newCachedThreadPool();
        raceInProgress = false;
        finishTimes = new HashMap<>();
    }

    public void start() {
        int port = DEFAULT_PORT;
        int attempts = 0;
        
        while (attempts < MAX_PORT_ATTEMPTS) {
            try {
                serverSocket = new ServerSocket(port);
                serverSocket.setReuseAddress(true);
                currentPort = port;
                System.out.println("Server started successfully on port " + currentPort);
                
                // Start race management thread
                new Thread(this::manageRaces).start();
                
                // Accept client connections
                while (true) {
                    try {
                        Socket clientSocket = serverSocket.accept();
                        System.out.println("New client connected");
                        
                        ClientHandler clientThread = new ClientHandler(clientSocket, this);
                        synchronized (this) {
                            clients.add(clientThread);
                        }
                        pool.execute(clientThread);
                    } catch (SocketException e) {
                        System.out.println("Server socket closed, stopping accept loop");
                        break;
                    }
                }
                return;
            } catch (IOException e) {
                System.err.println("Error on port " + port + ": " + e.getMessage());
                port++;
                attempts++;
            }
        }
        
        System.err.println("Failed to start server after " + MAX_PORT_ATTEMPTS + " attempts");
        System.exit(1);
    }

    private void manageRaces() {
        while (true) {
            try {
                synchronized (this) {
                    if (clients.size() >= 2 && !raceInProgress) {
                        startNewRace();
                    }
                }
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                System.out.println("Race management thread interrupted");
                break;
            }
        }
    }

    private synchronized void startNewRace() {
        currentSentence = SENTENCES[new Random().nextInt(SENTENCES.length)];
        raceInProgress = true;
        finishTimes.clear();
        raceStartTime = System.currentTimeMillis();
        
        broadcast("RACE_START", currentSentence);
        System.out.println("New race started with sentence: " + currentSentence);
        
        // End race after timeout
        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                synchronized (TypingRaceServer.this) {
                    if (raceInProgress) {
                        endRace();
                    }
                }
            }
        }, 60000);
    }

    private synchronized void endRace() {
        raceInProgress = false;
        StringBuilder results = new StringBuilder("Race results:\n");
        
        if (finishTimes.isEmpty()) {
            results.append("No one finished in time!\n");
        } else {
            finishTimes.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .forEachOrdered(entry -> {
                    double time = (entry.getValue() - raceStartTime) / 1000.0;
                    results.append(entry.getKey()).append(": ").append(time).append(" seconds\n");
                });
        }
        
        broadcast("RACE_END", results.toString());
        System.out.println("Race ended:\n" + results);
    }

    private synchronized void broadcast(String type, String message) {
        List<ClientHandler> clientsCopy;
        synchronized (this) {
            clientsCopy = new ArrayList<>(clients);
        }
        
        for (ClientHandler client : clientsCopy) {
            try {
                client.sendMessage(type + "|" + message);
            } catch (Exception e) {
                System.err.println("Error broadcasting to client: " + e.getMessage());
            }
        }
    }

    public synchronized void processCompletion(String username, String typedSentence) {
        if (raceInProgress && typedSentence.equals(currentSentence) ){
            long finishTime = System.currentTimeMillis();
            finishTimes.put(username, finishTime);
            
            if (finishTimes.size() == clients.size()) {
                endRace();
            }
        }
    }

    public synchronized void removeClient(ClientHandler client) {
        clients.remove(client);
        System.out.println(client.getUsername() + " disconnected");
        
        if (raceInProgress && clients.size() < 2) {
            endRace();
        }
    }

    private void shutdown() {
        try {
            if (pool != null) {
                pool.shutdown();
                if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                    pool.shutdownNow();
                }
            }
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException | InterruptedException e) {
            System.err.println("Error during shutdown: " + e.getMessage());
        }
    }

    public int getCurrentPort() {
        return currentPort;
    }

    public static void main(String[] args) {
        TypingRaceServer server = new TypingRaceServer();
        Runtime.getRuntime().addShutdownHook(new Thread(server::shutdown));
        server.start();
    }

    private class ClientHandler implements Runnable {
        private final Socket clientSocket;
        private final TypingRaceServer server;
        private PrintWriter out;
        private BufferedReader in;
        private String username;

        public ClientHandler(Socket socket, TypingRaceServer server) {
            this.clientSocket = socket;
            this.server = server;
        }

        public String getUsername() {
            return username;
        }

        @Override
        public void run() {
            try {
                out = new PrintWriter(clientSocket.getOutputStream(), true);
                in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));

                out.println("USERNAME_REQUEST");
                username = in.readLine();
                if (username == null || username.trim().isEmpty()) {
                    username = "Anonymous" + (int)(Math.random() * 1000);
                }
                
                System.out.println(username + " joined the game");
                out.println("WELCOME|Welcome to the Typing Race, " + username + "!");
                out.println("PORT|" + server.getCurrentPort());

                String inputLine;
                while ((inputLine = in.readLine()) != null) {
                    if (inputLine.equalsIgnoreCase("quit")) {
                        break;
                    }
                    server.processCompletion(username, inputLine);
                }
            } catch (IOException e) {
                System.err.println("Error with client " + username + ": " + e.getMessage());
            } finally {
                try {
                    server.removeClient(this);
                    if (out != null) out.close();
                    if (in != null) in.close();
                    if (clientSocket != null && !clientSocket.isClosed()) {
                        clientSocket.close();
                    }
                } catch (IOException e) {
                    System.err.println("Error closing client connection: " + e.getMessage());
                }
            }
        }

        public void sendMessage(String message) {
            if (out != null) {
                out.println(message);
            }
        }
    }
}