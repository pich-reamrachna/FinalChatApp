import java.io.*;
import java.net.*;
import java.util.Scanner;

public class Client {
    public static void main(String[] args) {
        // String host = "3.107.76.29";
        String host = "localhost";
        int port = 12345;

        Socket socket = null;
        Scanner scanner = null;
        PrintWriter out = null;
        Thread readerThread = null;

        try {
            // Connect to server
            socket = new Socket(host, port);
            System.out.println("Connected to chat server.\n");
            scanner = new Scanner(System.in);

            // Setup input/output streams
            InputStream is = socket.getInputStream();
            BufferedReader in = new BufferedReader(new InputStreamReader(is));
            out = new PrintWriter(socket.getOutputStream(), true);

            // === LOGIN FLOW ===
            while (true) {
                String prompt = in.readLine();
                if (prompt == null) {
                    System.out.println("Server closed connection.");
                    return;
                }

                System.out.print(prompt + " ");
                String input = scanner.nextLine();
                out.println(input);

                String response = in.readLine();
                if (response == null) {
                    System.out.println("Server closed connection.");
                    return;
                }

                System.out.println(response);

                if (response.toLowerCase().contains("login successful")) {
                    break; // logged in
                }

                // Check if another input is expected (like password or re-enter)
                if (response.endsWith(":") || response.endsWith(": ")) {
                    String input2 = scanner.nextLine().trim();
                    out.println(input2);

                    String feedback = in.readLine();
                    if (feedback == null) {
                        System.out.println("Server closed connection.");
                        return;
                    }

                    System.out.println(feedback);

                    if (feedback.toLowerCase().contains("login successful")) {
                        break;
                    }
                }

            }

            // Reader thread: listens for messages from server
            BufferedReader finalIn = in;
            readerThread = new Thread(() -> {
                try {
                    String response;
                    while ((response = finalIn.readLine()) != null) {
                        if (response.endsWith(":") || response.endsWith(": ")) {
                            System.out.print(response + " ");
                            System.out.flush();
                        } else {
                            System.out.println(response);
                        }
                    }
                } catch (IOException e) {
                    System.out.println("Connection closed by server.");
                }
            });
            readerThread.start();

            // Console input loop
            String msg;
            while (true) {
                if (scanner.hasNextLine()) {
                    msg = scanner.nextLine();

                    if ("/exit".equalsIgnoreCase(msg)) {
                        System.out.println("Closing connection...");
                        break;
                    }

                    if (out != null) {
                        out.println(msg);
                    }
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                if (scanner != null) scanner.close();
                if (out != null) out.close();
                if (socket != null && !socket.isClosed()) socket.close();
                if (readerThread != null) readerThread.join(); // Wait for thread to finish
            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
            }

            System.out.println("Client shut down.");
        }
    }
}
