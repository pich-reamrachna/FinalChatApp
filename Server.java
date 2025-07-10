import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A console based chat application with multi-room chat server with password authentication,
 * private messaging, room creation/joining, and friend management.
 */

public class Server {
    // Maps to track connected users, user passwords, chat rooms, and private message history. 
    private final static Map<String, ClientHandler> clients = new ConcurrentHashMap<>();
    private final static Map<String, String> userPasswords = new ConcurrentHashMap<>();
    private final static Map<String, ChatRoom> rooms = new ConcurrentHashMap<>();
    private final static Map<String, List<String>> privateChats = new ConcurrentHashMap<>();

    private static final int MAX_USERS = 10;
    private static final int MAX_ROOMS = 10;
    private static final int MAX_USERS_PER_ROOM = 10;
   

    // Tracking arrays
    private static final boolean[][] userRoomJoinHistory = new boolean[MAX_USERS][MAX_ROOMS];
    private static int currentUsers = 0;

    public static void main(String[] args) {
        // Define the port number the server will listens on 
        int port = 12345;

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Server started on port " + port + "...");

            // The server will run continuously
            while (true) {

                // Wait for and accept incoming client connections
                Socket socket = serverSocket.accept();

                synchronized (Server.class) {
                    if (currentUsers >= MAX_USERS) {
                        rejectConnection(socket);
                        continue;
                    }
                    currentUsers++;
                }
                System.out.println("\nNew connection accepted (" + currentUsers + "/" + MAX_USERS + " users)");
                // When a client connects, it creates a new thread to handle the client 
                new Thread(new ClientHandler(socket)).start(); 
            }
        } catch (IOException e) {
            System.err.println("Server error: " + e.getMessage());
        }
    }

    private static void rejectConnection(Socket socket) {
        try (PrintWriter tempOut = new PrintWriter(socket.getOutputStream(), true)) {
            tempOut.println("[Server] Maximum users (" + MAX_USERS + ") reached. Try again later.");
            socket.close();
            System.out.println("Rejected connection (server full)");
        } catch (IOException e) {
            System.out.println("Error during rejection: " + e.getMessage());
        }
    }

    /**
     * Represent a chat room with name, password, and members
     */
    static class ChatRoom {
        String roomName;
        String password;
        Set<ClientHandler> members = new HashSet<>();
        final int roomIndex;

        // Constructs a new chat room 
        ChatRoom(String name, String pass, int index) {
            this.roomName = name;
            this.password = pass;
            this.roomIndex = index;
        }

        // Broadcast message to all members in the room except the sender
        void broadcast(String message, ClientHandler sender) {
            members.forEach(member -> {
                if (member != sender) {
                    if (message.equals("joined the room") || message.equals("left the room")) {
                        // System notification format
                        member.out.println("[Server] "+ sender.username + " " + message);
                    } else {
                        // Regular chat message format
                        member.out.println("[" + sender.username + "]: " + message);
                    }
                }
            });
        }
    }

    /**
     * Class that handles individual client connections and manage their interactions 
     */
    static class ClientHandler implements Runnable {
        // Network communication
        private final Socket socket;            // Client connection socket
        private BufferedReader in;              // Input stream from client, client --> server
        private PrintWriter out;                // Output stream to client, server --> client

        // User information
        private String username;                // Unique user identifier
        private ChatRoom currentRoom = null;    // Currently joined chatroom
        private final Set<String> friends = new HashSet<>();    // User's friend list
        private String privateTarget = null;    // Current private chat recipient

        public ClientHandler(Socket socket) {
            this.socket = socket;   // Stores the client connection socket
        }

        @Override
        public void run() {
            try {
                // When new client connects
                System.out.println("\nNew client connected from: " + socket.getInetAddress().getHostAddress());

                setupStreams();
                
                if (!authenticateUser()) {
                    System.out.println("Connection closed during authentication");
                    return;
                }

                System.out.println("\nUser '"+ username + "' has joined the server.");

                // Main interaction loop
                while (true) {
                    showMainMenu(); // Display options
                    String choice = in.readLine();

                    // Handles empty input
                    if (choice == null) {
                        throw new IOException("Client disconnected");
                    }
                    
                    choice = choice.trim();

                    if (choice.equals("/exit")) {
                        out.println("[Server] Goodbye!");
                        out.flush();

                        // 2. Small delay to ensure message is sent
                        try { Thread.sleep(100); } catch (InterruptedException e) {}
                        
                        // 3. Close the socket
                        socket.close();
                        
                        // 4. Print disconnect message
                        System.out.println("User '" + username + "' disconnected via /exit");
                        
                        // 5. Return to trigger clea
                        return; // Exit normally without triggering exception
                    }

                    // Handles user's choice
                    switch (choice) {
                        case "1": handleJoinRoom(); break;
                        case "2": handleCreateRoom(); break;
                        case "3": handleFriendMenu(); break;               
                        default: out.println("Invalid option. Please type 1, 2, 3 or /exit to exit.");
                    }
                }
            } catch (IOException e) {
                System.out.println("\n" + (username != null ? username : "Client") + " disconnected.");   
            } finally {
                cleanup(); // Ensure that client is removed and resources closed
            }
        }

        // Initializes input and output streams for communication
        private void setupStreams() throws IOException {
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out = new PrintWriter(socket.getOutputStream(), true);
        }

        // Authenticate existing users or registers new users with password
        private boolean authenticateUser() throws IOException {
            while (true) {
                out.println("Enter username (or /exit to quit):");
                String input = in.readLine();

                // Exit condition
                if (input == null) return false;

                if (input.trim().equalsIgnoreCase("/exit")) {
                    System.out.println("User disconnected during authentication");
                    out.println("[Server] Goodbye! Disconnecting...");
                    out.flush();
                    return false;
                }
                

                username = input.trim();
                // Handle empty input
                if (username.isEmpty()) {
                    out.println("Username cannot be empty. Please try again.");
                    continue;
                }

                synchronized (userPasswords) {
                    // Handle existing username 
                    if (clients.containsKey(username)) {
                        out.println("The username is already taken. Please try again.");
                        continue;
                    }

                    // For existing user, verify password
                    if (userPasswords.containsKey(username)) {
                        out.println("Enter password:");
                        String password = in.readLine();

                        if (password == null || password.trim().isEmpty()) {
                            out.println("Password cannot be empty. Please try again.");
                            continue;
                        }
                        
                        if (userPasswords.get(username).equals(hashPassword(password))) {
                            break;  // Successful login
                        }
                        out.println("Incorrect password.");

                    } else {
                        // New user registration
                        out.println("New user. Set password:");
                        String password = in.readLine();
                        if (password == null || password.trim().isEmpty()) {
                            out.println("Password cannot be empty. Please try again.");
                            continue;
                        }

                        userPasswords.put(username, hashPassword(password));
                        break;
                    }
                }
            }

            // Registration complete
            clients.put(username, this); // Register client
            out.println("Login successful! Welcome " + username);
            return true;
        }

        // Displays the main menu options
        private void showMainMenu() {
            out.println("\n=== MAIN MENU ===");
            out.println("1. Join a Room");
            out.println("2. Create a Room");
            out.println("3. Friend Menu");
            out.println("Type /exit to quit");
            out.println("Enter:");
            out.flush();
        }

        // Show all existing rooms
        private boolean showAllRooms() {
            if (rooms.isEmpty()) {
                out.println("No rooms available. Please create one first.");
                return false;
            }
            out.println("\nAvailable Rooms:\n");
            rooms.forEach((name, room) -> 
                out.println("- " + name + " (" + room.members.size() + " members)"));
            out.flush();
            return true;
        }

        // Join an existing room after entering a correct password 
        private void handleJoinRoom() throws IOException {
            if (!showAllRooms()) {
                return;
            }
            while (true) {

                out.println("\nEnter room name (or /back to cancel):");
                String roomName = in.readLine().trim();

                // If input is /back, go back to main menu
                if (roomName.equalsIgnoreCase("/back")) return;

                // Handles empty input
                if (roomName.isEmpty()) {
                    out.println("Room name cannot be empty. Please try again.");
                    continue;
                }

                // Handles non-existing room 
                ChatRoom room = rooms.get(roomName);
                if (room == null) {
                    out.println("Room doesn't exist! Please try again.");
                    continue;
                }

                // Password verification
                while (true) { 
                    out.println("Enter password (or /back to cancel):");
                    String password = in.readLine().trim();

                    // Password can be empty, as it simulate a public chat room
                    if (password == null) return;

                    // If input is /back, go back to main menu
                    if (password.equalsIgnoreCase("/back")) {
                        out.println("Canceled joining room...");
                        return;
                    }

                    // Verify room password
                    if (!room.password.equals(password)) {
                        out.println("Wrong password! Try again.");
                    } else {
                        break;
                    }
                }

                enterRoom(room); // Enter chat room
                break;
            }
        }

        // Enter a room and handle user messages
        private void enterRoom(ChatRoom room) throws IOException {
            // Check room capacity
            synchronized (userRoomJoinHistory) {
                int usersInThisRoom = 0;
                // Count how many users are in this room (column scan)
                for (int i = 0; i < MAX_USERS; i++) {
                    if (userRoomJoinHistory[i][room.roomIndex]) {
                        usersInThisRoom++;
                    }
                }
                
                // Enforce per-room limit
                if (usersInThisRoom >= MAX_USERS_PER_ROOM) {
                    out.println("[Server] Room is full (max " + MAX_USERS_PER_ROOM + " users)");
                    return;
                }

                // 2. Reserve a slot for this user
                boolean slotFound = false;
                for (int i = 0; i < MAX_USERS; i++) {
                    if (!userRoomJoinHistory[i][room.roomIndex]) {
                        userRoomJoinHistory[i][room.roomIndex] = true;
                        slotFound = true;
                        break;
                    }
                }
                
                if (!slotFound) { 
                    out.println("[Server] Error: No available slots");
                    return;
                }
            }

            // Proceed with joining
            currentRoom = room;
            room.members.add(this); 
            room.broadcast("joined the room", this);
            out.println("\nYou're in '" + room.roomName + "'. Type /back to leave.");

            // Room message loop
            String message;
            while ((message = in.readLine()) != null) {

                // If user message is /back, leave chat room  
                if (message.equalsIgnoreCase("/back")) {
                    leaveCurrentRoom();
                    break;
                }

                // Handles empty messages
                if (message.trim().isEmpty()) {
                    out.println("(Empty message not sent)");
                    continue;
                }
                currentRoom.broadcast(message, this);
            }
        }

        // Create a new chat room
        private void handleCreateRoom() throws IOException {
            // Check MAX_ROOMS limit
            synchronized (rooms) {
                if (rooms.size() >= MAX_ROOMS) {
                    out.println("[Server] Maximum rooms (" + MAX_ROOMS + ") reached. Cannot create more.");
                    return;
                }
            }

            while (true) {
                out.println("Enter new room name (or /back to cancel):");
                String roomName = in.readLine();

                // Handle client disconnect
                if (roomName == null) {
                    throw new IOException("Client disconnected during room creation");
                }

                roomName = roomName.trim();
                
                // If input is /back, go back to main menu
                if (roomName.equalsIgnoreCase("/back")) return;

                // If input is empty, prompt user to input again
                if (roomName.isEmpty()) {
                    out.println("Room name cannot be empty. Please try again.\n");
                    continue;
                }

                // If room name already exists, prompt user to input again
                synchronized (rooms) {
                    if (rooms.containsKey(roomName)) {
                        out.println("Room already exists. Choose another name.");
                        continue;
                    }

                    // Find available room index
                    int roomIndex = rooms.size();

                    out.println("Set password for '" + roomName + "':");
                    String password = in.readLine();

                    if (password == null) {
                        throw new IOException("Client disconnected during password input");
                    }
                    password = password.trim();

                    ChatRoom newRoom = new ChatRoom(roomName, password, roomIndex);
                    rooms.put(roomName, newRoom);
                    enterRoom(newRoom);
                    break;
                }
            }
        }

        // Leave the current room and notify others
        private void leaveCurrentRoom() {
            if (currentRoom != null) {
                synchronized (userRoomJoinHistory) {
                    // Free this user's slot in the room
                    for (int i = 0; i < MAX_USERS; i++) {
                        if (userRoomJoinHistory[i][currentRoom.roomIndex]) {
                            userRoomJoinHistory[i][currentRoom.roomIndex] = false;
                            break;
                        }
                    }
                }
        
                    currentRoom.members.remove(this);
                    currentRoom.broadcast("left the room", this);
                    currentRoom = null;
                    out.println("[Server] You left the room");
            }
        }

        // Handles viewing, adding, and messaging friends
        private void handleFriendMenu() throws IOException {
            while (true) {
                out.println("\n=== FRIEND MENU ===");
                out.println("1. View friends");
                out.println("2. Add friend");
                out.println("3. Message friend");
                out.println("4. Back to main");
                out.println("Enter: ");
                out.flush();
                
                String input = in.readLine();

                // Check for client disconnect
                if (input == null) {
                    throw new IOException("Client disconnected during friend menu");
                }

                input = input.trim();

                // Check for empty input
                if (input.isEmpty()) {
                    out.println("Input cannot be empty. Please enter a Friend Menu option.");
                    continue;
                }

                // Process user choice
                switch (input) {
                    case "1": showFriends(); break;
                    case "2": addFriend(); break;
                    case "3": startPrivateChat(); break;
                    case "4": return;
                    case "/exit":
                        out.println("[Server] Goodbye! Disconnecting ...");
                        out.flush();
                        throw new IOException("Normal exit from friend menu");
                    default: out.println("Invalid option. Please enter 1, 2, 3, or 4.");
                }
            }
        }

        // Show list of friends
        private void showFriends() {
            if (friends.isEmpty()) {
                out.println("You have no friends yet.");
                return;
            }
            out.println("\n=== Your Friends ===");
            
            for (String friend : friends) {
                boolean isOnline = clients.containsKey(friend);
                out.println("- " + friend + " [" + (isOnline ? "Online" : "Offline") + "]");
            }
        }

        // Add another online user to the friend list
        private void addFriend() throws IOException {
            while (true) { 
                out.println("\nEnter your friend's username (or /back to cancel):");
                String friend = in.readLine().trim();

                // If input is /back, return back to friend menu
                if (friend.equalsIgnoreCase("/back")) {
                    out.println("Canceling Adding Friends...");
                    return;
                }
                
                // Check for empty input
                if (friend.isEmpty()) {
                    out.println("Username cannot be empty. Please try again.");
                    continue;
                }
                
                // Check if friend exist even if not online
                if (!userPasswords.containsKey(friend)) {
                    out.println("User does not exist. Please try again.");
                } else if (friend.equals(username)) {
                    out.println("You can't add yourself!");
                } else if (friends.contains(friend)) {
                    out.println(friend + " is already in your friend list.");
                } else {
                    friends.add(friend);
                    out.println(friend + " has been added to your friend list.");
                    return;
                }
            }
        }

        // Start a private chat with a friend
        private void startPrivateChat() throws IOException {
            while (true) { 
                out.println("\nEnter your friend's username to chat with (or /back to cancel):");
                String target = in.readLine().trim();

                // Do /back to go back to menu
                if (target.equalsIgnoreCase("/back")) {
                    out.println("Private chat cancelled...");
                    return;
                }

                // Handles empty input
                if (target.isEmpty()) {
                    out.println("Username cannot be empty. Please try again.");
                    continue;
                }

                // Handles non existing friend
                if (!friends.contains(target)) {
                    out.println("Not in your friends list. Please try again.");
                    continue;
                }

                // When the friend is not connected to the server
                ClientHandler targetHandler = clients.get(target);
                if (targetHandler == null) {
                    out.println("User is currently offline.");
                    return;
                }

                // Generate consistent chat key (alphabetical order)
                String chatKey = username.compareTo(target) < 0 ? 
                    username + "::" + target : target + "::" + username;
            
                // Display previous messages if available
                List<String> history = privateChats.getOrDefault(chatKey, new ArrayList<>());
                if (!history.isEmpty()) {
                    out.println("\n--- Chat History ---");
                    history.forEach(out::println);
                    out.println("-------------------");
                }

                // Start private chat session
                privateTarget = target;
                out.println("\n[Private chat with " + target + "] (type /back to leave the DMs)");
                
                String message;
                while ((message = in.readLine()) != null) {

                    // If message is /back, leave DMs
                    if (message.equalsIgnoreCase("/back")) {
                        privateTarget = null;
                        break;
                    }

                    // Check for empty input
                    if (message.trim().isEmpty()) {
                        out.println("(Empty message not sent)");
                        continue;
                    }

                    // Store message in history
                    String formattedMsg = "[" + username + "]: " + message;
                    privateChats.computeIfAbsent(chatKey, k -> new ArrayList<>()).add(formattedMsg);

                    // Deliver message
                    if (targetHandler.privateTarget != null &&
                        targetHandler.privateTarget.equals(username)) {
                        // Target is in chat with us - show immediately
                        targetHandler.out.println(formattedMsg);
                    } 
                }
                break;
            }  
        }

        private void cleanup() {
            leaveCurrentRoom();

            synchronized (Server.class) {
                if (username != null) {
                    clients.remove(username);
                }
                Server.currentUsers--;
                // This will now show for all disconnections
                System.out.println("\nNow has (" + Server.currentUsers + "/" + MAX_USERS + " users)");
            }

            try {
                if (in != null) in.close();
                if (out != null) out.close();
                if (socket != null) socket.close();
            } catch (IOException e) {
                System.out.println("Cleanup error: " + e.getMessage());
            }
        }
    }

    // Password hashing with SHA-256
    private static String hashPassword(String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(password.getBytes());
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("Hashing failed", e);
        }
    }
}
