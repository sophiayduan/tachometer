import java.io.ByteArrayOutputStream;
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

public class WebSocket {
    public static void main(String[] args)
            throws IOException, NoSuchAlgorithmException {
        ServerSocket server = new ServerSocket(8080); //8080 port for websocket
        try {
            System.out.println(
                    "Waiting for a connection..."
            );
            Socket client = server.accept();
            System.out.println("Client connected: " + client.getInetAddress());

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
                    System.out.println("WebSocket handshake complete");

                    while (true) {
                        byte[] header = in.readNBytes(2);
                        if (header.length < 2) {
                            System.out.println("Connection closed");
                            break;
                        }

                        int payloadLen = header[1] & 0x7F; // mask bit stripped


                        byte[] maskKey = in.readNBytes(4);
                        byte[] encoded = in.readNBytes(payloadLen);
                        byte[] decoded = new byte[payloadLen];

                        for (int i = 0; i < payloadLen; i++) {
                            decoded[i] = (byte) (encoded[i] ^ maskKey[i & 0x3]);
                        }

                        String message = new String(decoded, "UTF-8");
                        System.out.println("Received: " + message);



                        double hallValue = parseHallValue(message);
                        if (hallValue >= 0) {
                            double mag = calculateMagneticField(hallValue);
                            sendMessage(out, String.format("Magnetic field: %.2f mT", mag));
                        }
                    }
                }
            } finally {
                s.close();
            }
        } finally {
            server.close();
        }
    }


    static double parseHallValue(String json) {

        Pattern pattern = Pattern.compile("\"hall_mT\":(\\d+)");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            return Double.parseDouble(matcher.group(1));
        }
        return -1;
    }

    static double calculateMagneticField(double rawValue) {
        double voltage = (rawValue / 4095.0) * 3.3;
        double zeroLevel = 2.5; // maxv /2
        double sensitivity = 0.0025; // tbd
        double gauss = (voltage - zeroLevel) / sensitivity;
        return gauss * 0.1; // convert Gauss to milliTesla
    }

    static void sendMessage(OutputStream out, String msg) throws IOException {
        byte[] payload = msg.getBytes("UTF-8");
        int len = payload.length;

        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(0x81); 

        if (len <= 125) {
            frame.write(len); 
        } else if (len <= 65535) {
            frame.write(126);
            frame.write((len >> 8) & 0xFF);
            frame.write(len & 0xFF);
        } else {
            frame.write(127);
            for (int i = 7; i >= 0; i--) {
                frame.write((len >> (8 * i)) & 0xFF);
            }
        }

        frame.write(payload);

        out.write(frame.toByteArray());
        out.flush();
    }
}

