import java.io.*;
import java.net.*;
import java.util.concurrent.*;

public class OffshoreProxy {

    private static final int PORT = 9090;

    public static void main(String[] args) {
        System.out.println("OffshoreProxy starting on port " + PORT);
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            ExecutorService threadPool = Executors.newCachedThreadPool();

            while (true) {
                Socket shipSocket = serverSocket.accept();
                System.out.println("Accepted connection from ShipProxy: " + shipSocket.getInetAddress());
                threadPool.submit(new OffshoreConnectionHandler(shipSocket));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}

class OffshoreConnectionHandler implements Runnable {
    private final Socket socket;

    public OffshoreConnectionHandler(Socket socket) {
        this.socket = socket;
    }

    public void run() {
        try (DataInputStream in = new DataInputStream(socket.getInputStream());
             DataOutputStream out = new DataOutputStream(socket.getOutputStream())) {

            while (true) {
                int length = in.readInt();
                byte[] requestBytes = new byte[length];
                in.readFully(requestBytes);
                handleHttpRequest(requestBytes, in, out);
            }
        } catch (IOException e) {
            System.err.println("Offshore handler error: " + e.getMessage());
        }
    }

    private void handleHttpRequest(byte[] requestBytes, DataInputStream in, DataOutputStream out) {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(requestBytes);
             BufferedReader reader = new BufferedReader(new InputStreamReader(bais))) {

            String initialLine = reader.readLine();
            if (initialLine == null) return;

            boolean isConnect = initialLine.startsWith("CONNECT");
            String host = "";
            int port = isConnect ? 443 : 80;

            if (isConnect) {
                String[] hp = initialLine.split(" ")[1].split(":");
                host = hp[0];
                port = Integer.parseInt(hp[1]);
            } else {
                String line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    if (line.toLowerCase().startsWith("host:")) {
                        String[] hp = line.substring(5).trim().split(":");
                        host = hp[0];
                        if (hp.length > 1) port = Integer.parseInt(hp[1]);
                    }
                }
            }

            try (Socket targetSocket = new Socket(host, port)) {
                InputStream targetIn = targetSocket.getInputStream();
                OutputStream targetOut = targetSocket.getOutputStream();

                if (isConnect) {
                    byte[] response = "HTTP/1.1 200 Connection Established\r\n\r\n".getBytes();
                    synchronized (out) {
                        out.writeInt(response.length);
                        out.write(response);
                        out.flush();
                    }
                    forwardStreams(in, out, targetIn, targetOut);
                } else {
                    targetOut.write(requestBytes);
                    targetOut.flush();

                    ByteArrayOutputStream responseBuffer = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = targetIn.read(buffer)) != -1) {
                        responseBuffer.write(buffer, 0, bytesRead);
                        if (targetIn.available() == 0) break;
                    }

                    byte[] response = responseBuffer.toByteArray();
                    synchronized (out) {
                        out.writeInt(response.length);
                        out.write(response);
                        out.flush();
                    }
                }
            }

        } catch (Exception e) {
            System.err.println("Failed to process HTTP request: " + e.getMessage());
        }
    }

    private void forwardStreams(DataInputStream shipIn, DataOutputStream shipOut, InputStream remoteIn, OutputStream remoteOut) {
        Thread upstream = new Thread(() -> {
            try {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = shipIn.read(buffer)) != -1) {
                    remoteOut.write(buffer, 0, len);
                    remoteOut.flush();
                }
            } catch (IOException ignored) {}
        });

        Thread downstream = new Thread(() -> {
            try {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = remoteIn.read(buffer)) != -1) {
                    shipOut.write(buffer, 0, len);
                    shipOut.flush();
                }
            } catch (IOException ignored) {}
        });

        upstream.start();
        downstream.start();
        try {
            upstream.join();
            downstream.join();
        } catch (InterruptedException ignored) {}
    }
}
