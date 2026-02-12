import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// code from websocket guide

public class WebSocket {
    public static void main(String[] args)
            throws IOException, NoSuchAlgorithmException {
        ServerSocket server = new ServerSocket(8080);
        try {
            System.out.println(
                    "Server has started on 10.178.189.125 \r\nWaiting for a connection..."
            );
            Socket client = server.accept();
            System.out.println("Client connected.");

            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();
            Scanner s = new Scanner(in, "UTF-8");

            try {
                String data = s.useDelimiter("\\r\\n\\r\\n").next();
                Matcher get = Pattern.compile("^GET").matcher(data);

                if (get.find()) {
                    Matcher match = Pattern.compile(
                            "Sec-WebSocket-Key: (.*)"
                    ).matcher(data);
                    match.find();

                    byte[] response = (
                            "HTTP/1.1 101 Switching Protocols\r\n" +
                                    "Connection: Upgrade\r\n" +
                                    "Upgrade: websocket\r\n" +
                                    "Sec-WebSocket-Accept: " +
                                    Base64.getEncoder().encodeToString(
                                            MessageDigest.getInstance("SHA-1").digest(
                                                    (
                                                            match.group(1).trim() +
                                                                    "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
                                                    ).getBytes("UTF-8")
                                            )
                                    ) +
                                    "\r\n\r\n"
                    ).getBytes("UTF-8");

                    out.write(response, 0, response.length);
                    out.flush();

                    while (true) {
                        byte[] header = in.readNBytes(2);
                        if (header.length < 2) break;

                        int payloadLen = header[01] & 0x7F; // mask bit stripped

                        byte[] maskKey = in.readNBytes(4);
                        byte[] encoded = in.readNBytes(payloadLen);
                        byte[] decoded = new byte[payloadLen];

                        for (int i = 0; i < payloadLen; i++) {
                            decoded[i] = (byte) (encoded[i] ^ maskKey[i & 0x3]);
                        }

                        printMag();
                    }
                }
            } finally {
                s.close();
            }
        } finally {
            server.close();
        }
    }

    void printMag() {
        // get zero value from input? hard coded for now
        double zeroLevel = 0.5; // temp
        double gaussPerStep = 2.57;
        double decoded = 1.002;
        double mag = (decoded - zeroLevel) * gaussPerStep;
        System.out.println(mag);
    }
}

