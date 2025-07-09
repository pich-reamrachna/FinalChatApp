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

    public static void main(String[] args) {
        int port = 12345;
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Server started on port " + port + "...");
            while (true) {
                Socket socket = serverSocket.accept();
                new Thread(new ClientHandler(socket)).start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Class representing a chat room with name, password, and members
     */
    static class ChatRoom {
        String roomName;
        String password;
        Set<ClientHandler> members = new HashSet<>();

        ChatRoom(String name, String pass) {
            this.roomName = name;
            this.password = pass;
        }

        // Broadcase message to all members in the room except the sender
        void broadcast(String message, ClientHandler sender) {
            members.forEach(member -> {
                if (member != sender) {
                    if (message.equals("joined the room") || message.equals("left the room")) {
                        member.out.println("[Server]: "+ sender.username + " " + message);
                    } else {
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
        private final Socket socket;
        private BufferedReader in;
        private PrintWriter out;
        private String username;
        private ChatRoom currentRoom = null;
        private final Set<String> friends = new HashSet<>();
        private String privateTarget = null;

        public ClientHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try {
                setupStreams();
                if (!authenticateUser()) return; // Login or register user
                
                // Main interaction loop
                while (true) {
                    showMainMenu();
                    String choice = in.readLine().trim();
                    if (choice == null) break;

                    // Handles empty input
                    if (choice.isEmpty()) {
                        out.println("Input cannot be empty. Please enter a menu option.");
                        continue;
                    }
                    // Handles user's choice
                    switch (choice) {
                        case "1": handleJoinRoom(); break;
                        case "2": handleCreateRoom(); break;
                        case "3": handleFriendMenu(); break;
                        case "/exit": return;
                        default: out.println("Invalid option. Please choose 1, 2 or 3.");
                    }
                }
            } catch (IOException e) {
                System.out.println(username + " disconnected abruptly");
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
                username = in.readLine().trim();
                if (username == null || username.equalsIgnoreCase("/exit")) return false;

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

                    // For existing user, ask for password
                    if (userPasswords.containsKey(username)) {
                        out.println("Enter password:");
                        String password = in.readLine();

                        if (password == null || password.trim().isEmpty()) {
                            out.println("Password cannot be empty. Please try again.");
                            continue;
                        }
                        
                        if (userPasswords.get(username).equals(hashPassword(password))) {
                            break;
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
            out.println("\nAvailable Rooms:");
            rooms.forEach((name, room) -> 
                out.println("\n" + "- " + name + " (" + room.members.size() + " members)"));
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

                if (roomName.equalsIgnoreCase("/back")) return;

                if (roomName.isEmpty()) {
                    out.println("Room name cannot be empty. Please try again.");
                    continue;
                }

                ChatRoom room = rooms.get(roomName);
                if (room == null) {
                    out.println("Room doesn't exist! Please try again.");
                    continue;
                }

                while (true) { 
                    out.println("Enter password (or /back to cancel):");
                    String password = in.readLine().trim();

                    if (password == null) return;

                    if (password.equalsIgnoreCase("/back")) {
                        out.println("Canceled joining room...");
                        return;
                    }

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
            currentRoom = room;
            room.members.add(this);
            room.broadcast("joined the room", this);
            out.println("\nYou're in '" + room.roomName + "'. Type /back to leave.");

            String message;
            while ((message = in.readLine()) != null) {
                if (message.equalsIgnoreCase("/back")) {
                    leaveCurrentRoom();
                    break;
                }
                if (message.trim().isEmpty()) {
                    out.println("(Empty message not sent)");
                    continue;
                }
                currentRoom.broadcast(message, this);
            }
        }

        // Create a new chat room
        private void handleCreateRoom() throws IOException {
            while (true) {
                out.println("Enter new room name (or /back to cancel):");
                String roomName = in.readLine().trim();
                
                // If input is /back, go back to main menu
                if (roomName.equalsIgnoreCase("/back")) return;

                // If input is empty, prompt user to input again
                if (roomName.isEmpty()) {
                    out.println("Room name cannot be empty. Please try again.\n");
                    continue;
                }

                // If room name already exists, prompt user to input again
                if (rooms.containsKey(roomName)) {
                    out.println("Room already exists. Choose another name.");
                    continue;
                }

                out.println("Set password for '" + roomName + "':");
                String password = in.readLine().trim();

                ChatRoom newRoom = new ChatRoom(roomName, password);
                rooms.put(roomName, newRoom);
                enterRoom(newRoom);
                break;
            }
        }

        // Leave the current room and notify others
        private void leaveCurrentRoom() {
            if (currentRoom != null) {
                currentRoom.members.remove(this);
                currentRoom.broadcast("left the room", this);
                currentRoom = null;
                out.println("You left the room");
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
                
                String input = in.readLine().trim();
                if (input == null) break;

                if (input.isEmpty()) {
                    out.println("Input cannot be empty. Please enter a Friend Menu option.");
                    continue;
                }

                switch (input) {
                    case "1": showFriends(); break;
                    case "2": addFriend(); break;
                    case "3": startPrivateChat(); break;
                    case "4": return;
                    default: out.println("Invalid option. Please enter 1, 2, 3, or 4.");
                }
            }
        }

        // Show list of friends
        private void showFriends() {
            out.println("Your friends: " + friends);
        }

        // Add another online user to the friend list
        private void addFriend() throws IOException {
            while (true) { 
                out.println("\nEnter your friend's username (or /back to cancel):");
                String friend = in.readLine().trim();

                if (friend.equalsIgnoreCase("/back")) {
                    out.println("Canceling Adding Friends...");
                    return;
                }
                
                if (friend.isEmpty()) {
                    out.println("Username cannot be empty. Please try again.");
                    continue;
                }

                 if (clients.containsKey(friend)) {
                friends.add(friend);
                out.println(friend + " added!");
                return;
            
                } else {
                out.println("User not found. Please try again.");
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
                    if (message.equalsIgnoreCase("/back")) {
                        privateTarget = null;
                        break;
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

        // Cleanup resources on client disconnect
        private void cleanup() {
            try {
                leaveCurrentRoom();
                clients.remove(username);
                if (in != null) in.close();
                if (out != null) out.close();
                if (socket != null) socket.close();
            } catch (IOException e) {
                e.printStackTrace();
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
