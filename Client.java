import java.io.*;
import java.net.*;
import java.util.Scanner;

public class Client {
    public static void main(String[] args) {
        String host = "localhost";
        int port = 12345;

        Socket socket = null;
        Scanner scanner = null;
        PrintWriter out = null;
        Thread readerThread = null;

        try {
            // Connect to server
            socket = new Socket(host, port);
            scanner = new Scanner(System.in);

            // Setup input/output streams
            InputStream is = socket.getInputStream();
            BufferedReader in = new BufferedReader(new InputStreamReader(is));
            out = new PrintWriter(socket.getOutputStream(), true);

            // === ADDED: Check first server response for "Maximum users" ===
            String firstResponse = in.readLine();
            if (firstResponse == null || firstResponse.contains("Maximum users")) {
                System.out.println(firstResponse != null ? firstResponse : "Server rejected connection");
                System.out.println("Disconnecting...");
                return;
            }

            System.out.println("Connected to chat server.\n");

            // === LOGIN FLOW (rest remains exactly the same) ===
            System.out.print(firstResponse + " ");
            String input = scanner.nextLine();
            out.println(input);

            while (true) {
                String response = in.readLine();
                if (response == null) {
                    System.out.println("Server closed connection.");
                    return;
                }

                System.out.println(response);
                if (response.toLowerCase().contains("login successful")) break;

                if (response.endsWith(":") || response.endsWith(": ")) {
                    String input2 = scanner.nextLine().trim();
                    out.println(input2);
                }
            }

            // Reader thread (removed Maximum users check from here)
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

            // Console input loop (unchanged)
            String msg;
            while (scanner.hasNextLine()) {
                msg = scanner.nextLine();
                if ("/exit".equalsIgnoreCase(msg)) break;
                out.println(msg);
            }

        } catch (ConnectException e) {
            System.out.println("Could not connect to server. It may be full or offline.");
        } catch (SocketException e) {
            System.out.println("Connection closed by server.");
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                if (scanner != null) scanner.close();
                if (out != null) out.close();
                if (socket != null) socket.close();
                if (readerThread != null) readerThread.join();
            } catch (Exception e) {
                System.out.println("Cleanup error: " + e.getMessage());
            }
            System.out.println("Client shut down.");
        }
    }
}