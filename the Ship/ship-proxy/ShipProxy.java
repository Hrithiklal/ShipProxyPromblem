import java.io.*;
import java.net.*;
import java.util.concurrent.*;

public class ShipProxy {

    private static final int LOCAL_PORT = 8080;
    private static final String OFFSHORE_HOST = "offshore";
    private static final int OFFSHORE_PORT = 9090;

    public static void main(String[] args) {
        System.out.println("ShipProxy starting on port " + LOCAL_PORT);
        try (ServerSocket serverSocket = new ServerSocket(LOCAL_PORT);
             Socket offshoreSocket = new Socket(OFFSHORE_HOST, OFFSHORE_PORT)) {

            DataOutputStream offshoreOut = new DataOutputStream(offshoreSocket.getOutputStream());
            DataInputStream offshoreIn = new DataInputStream(offshoreSocket.getInputStream());
            ExecutorService threadPool = Executors.newCachedThreadPool();

            while (true) {
                Socket clientSocket = serverSocket.accept();
                threadPool.submit(new ShipRequestHandler(clientSocket, offshoreOut, offshoreIn));
            }
        } catch (IOException e) {
            System.err.println("ShipProxy error: " + e.getMessage());
        }
    }
}

class ShipRequestHandler implements Runnable {
    private final Socket client;
    private final DataOutputStream offshoreOut;
    private final DataInputStream offshoreIn;

    public ShipRequestHandler(Socket client, DataOutputStream offshoreOut, DataInputStream offshoreIn) {
        this.client = client;
        this.offshoreOut = offshoreOut;
        this.offshoreIn = offshoreIn;
    }

    public void run() {
        try (Socket socket = client;
             InputStream clientIn = socket.getInputStream();
             OutputStream clientOut = socket.getOutputStream()) {

            byte[] request = readHttpRequest(clientIn);

            synchronized (offshoreOut) {
                offshoreOut.writeInt(request.length);
                offshoreOut.write(request);
                offshoreOut.flush();
            }

            byte[] response = readFramedResponse(offshoreIn);
            clientOut.write(response);
            clientOut.flush();
        } catch (IOException e) {
            System.err.println("Error handling client: " + e.getMessage());
        }
    }

    private byte[] readHttpRequest(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int len;
        while ((len = in.read(buffer)) != -1) {
            baos.write(buffer, 0, len);
            if (containsEndOfHeaders(baos.toByteArray())) break;
        }
        return baos.toByteArray();
    }

    private boolean containsEndOfHeaders(byte[] data) {
        String s = new String(data, 0, Math.min(data.length, 8192));
        return s.contains("\r\n\r\n");
    }

    private byte[] readFramedResponse(DataInputStream in) throws IOException {
        int len = in.readInt();
        byte[] response = new byte[len];
        in.readFully(response);
        return response;
    }
}
