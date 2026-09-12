package com.winlator.steampipeserver;


import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import timber.log.Timber;

public class SteamPipeServer {
    private static final int PORT = 34865;
    private ServerSocket serverSocket;
    private boolean running;

    private int readNetworkInt(DataInputStream input) throws IOException {
        return Integer.reverseBytes(input.readInt());
    }

    private void writeNetworkInt(DataOutputStream output, int value) throws IOException {
        output.writeInt(Integer.reverseBytes(value));
    }

    public void start() {
        stop();
        running = true;
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new InetSocketAddress(PORT));
                Timber.tag("SteamPipeServer").d("Server started on port " + PORT);

                while (running) {
                    Socket clientSocket = serverSocket.accept();
                    // clientSocket.setTcpNoDelay(true);
                    // clientSocket.setSoTimeout(5000);  // 5 second timeout
                    handleClient(clientSocket);
                }
            } catch (IOException e) {
                Timber.tag("SteamPipeServer").e(e, "Server error");
            }
        }).start();
    }

    private void handleClient(Socket clientSocket) {
        new Thread(() -> {
            try {
                DataInputStream input = new DataInputStream(
                        new BufferedInputStream(clientSocket.getInputStream()));
                DataOutputStream output = new DataOutputStream(
                        new BufferedOutputStream(clientSocket.getOutputStream()));

                while (running && !clientSocket.isClosed()) {
                    if (input.available() > 0) {
                        int messageType = readNetworkInt(input);
                        // Log.d("SteamPipeServer", "Received message: " + messageType);

                        switch (messageType) {
                            case RequestCodes.MSG_INIT:
                                Timber.tag("SteamPipeServer").d("Received MSG_INIT");
                                writeNetworkInt(output, 1);
                                output.flush();
                                break;
                            case RequestCodes.MSG_SHUTDOWN:
                                Timber.tag("SteamPipeServer").d("Received MSG_SHUTDOWN");
                                clientSocket.close();
                                break;
                            case RequestCodes.MSG_RESTART_APP:
                                Timber.tag("SteamPipeServer").d("Received MSG_RESTART_APP");
                                int appId = input.readInt();
                                writeNetworkInt(output, 0); // Send restart not needed
                                break;
                            case RequestCodes.MSG_IS_RUNNING:
                                Timber.tag("SteamPipeServer").d("Received MSG_IS_RUNNING");
                                writeNetworkInt(output, 1); // Send Steam running status
                                break;
                            case RequestCodes.MSG_REGISTER_CALLBACK:
                                Timber.tag("SteamPipeServer").d("Received MSG_REGISTER_CALLBACK");
                                break;
                            case RequestCodes.MSG_UNREGISTER_CALLBACK:
                                Timber.tag("SteamPipeServer").d("Received MSG_UNREGISTER_CALLBACK");
                                break;
                            case RequestCodes.MSG_RUN_CALLBACKS:
                                Timber.tag("SteamPipeServer").d("Received MSG_RUN_CALLBACKS");
                                break;
                            default:
                                Timber.tag("SteamPipeServer").w("Unknown message type: " + messageType);
                                break;
                        }
                    }
                }
            } catch (IOException e) {
                Timber.tag("SteamPipeServer").e(e, "Client handler error");
            }
        }).start();
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException e) {
            Timber.e(e);
        }
    }
}
