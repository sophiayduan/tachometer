import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Scanner;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Server {

    // All connected browser WebSocket clients
    static CopyOnWriteArrayList<OutputStream> browserClients = new CopyOnWriteArrayList<>();

    // For calculating RPM from rawData.ino's data
    static double peakThreshold = 50;   // BASED ON MAGNET STRENGTH ** TUNE THIS ** its like approx. half of max
    static boolean aboveThreshold = false;
    static long lastPeakTime = -1;
    static double currentRpm = 0;
    static double peakRpm = 0;

    // averaging 5 readings, for better rpm accurary (in theory)
    static double[] rpmHistory = new double[5];
    static int rpmIndex = 0;
    static int rpmCount = 0;
    static long RPM_TIMEOUT_MS = 2000; // if no peak detected for this long, RPM resets to 0

    public static void main(String[] args) throws IOException {

        new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(100); // check every 100ms
                    long now = System.currentTimeMillis();
                    if (lastPeakTime > 0 && (now - lastPeakTime) > RPM_TIMEOUT_MS) {
                        currentRpm = 0;
                        rpmCount = 0; // reset valid reading count
                        java.util.Arrays.fill(rpmHistory, 0);
                        // Broadcast the zeroed RPM to the browser
                        String json = String.format(
                            "{\"magneticField\":%.2f,\"rpm\":%.0f,\"peakRpm\":%.0f}",
                            0.0, currentRpm, peakRpm
                        );
                        broadcastToBrowsers(json);
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }).start();

        // Browser server (local website) on port 8081
        // It communicates w/ index.html over HTTP, then upgrades to WebSocket
        new Thread(() -> {
            try {
                ServerSocket browserServer = new ServerSocket(8081);
                System.out.println("Browser server listening on port 8081...");
                while (true) {
                    Socket client = browserServer.accept();
                    new Thread(() -> handleBrowserClient(client)).start();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }).start();

        // WebSocket server on 8080 to connected to ESP32 
        ServerSocket esp32Server = new ServerSocket(8080);
        try {
            System.out.println("Waiting for connection on port 8080...");
            while (true) {
                Socket client = esp32Server.accept();
                System.out.println("ESP32 connected!! " + client.getInetAddress()); 
                new Thread(() -> handleEsp32Client(client)).start();
            }
        } finally {
            esp32Server.close();
        }
    }

    static void handleBrowserClient(Socket client) {
        try {
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();
            Scanner s = new Scanner(in, "UTF-8");

            String data = s.useDelimiter("\\r\\n\\r\\n").next();

            // Check if this is a WebSocket upgrade request
            Matcher wsMatcher = Pattern.compile("Upgrade: websocket", Pattern.CASE_INSENSITIVE).matcher(data);
            Matcher keyMatcher = Pattern.compile("Sec-WebSocket-Key: (.*)").matcher(data);

            if (wsMatcher.find() && keyMatcher.find()) {
                // WebSocket handshake
                byte[] response = (
                        "HTTP/1.1 101 Switching Protocols\r\n" +
                        "Upgrade: websocket\r\n" +
                        "Connection: Upgrade\r\n" +
                        "Sec-WebSocket-Accept: " +
                        Base64.getEncoder().encodeToString(
                                MessageDigest.getInstance("SHA-1").digest(
                                        (keyMatcher.group(1).trim() + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
                                                .getBytes("UTF-8")
                                )
                        ) + "\r\n\r\n"
                ).getBytes("UTF-8");

                out.write(response);
                out.flush();
                System.out.println("Browser WebSocket handshake complete");

                browserClients.add(out);

                // Keep connection open, discard any incoming frames
                try {
                    while (true) {
                        if (in.read() == -1) break;
                    }
                } finally {
                    browserClients.remove(out);
                    System.out.println("Browser disconnected");
                }

            } else {
                // Plain HTTP request
                byte[] body = Files.readAllBytes(Paths.get("site/script.js"));
                String response =
                        "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: text/html\r\n" +
                        "Content-Length: " + body.length + "\r\n" +
                        "Connection: close\r\n\r\n";
                out.write(response.getBytes("UTF-8"));
                out.write(body);
                out.flush();
                client.close();
            }

        } catch (IOException | NoSuchAlgorithmException e) {
            System.out.println("Browser client error: " + e.getMessage());
        }
    }

    static void handleEsp32Client(Socket client) {
        try {
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();
            Scanner s = new Scanner(in, "UTF-8");

            String data = s.useDelimiter("\\r\\n\\r\\n").next();
            Matcher get = Pattern.compile("^GET").matcher(data);

            if (get.find()) {
                Matcher match = Pattern.compile("Sec-WebSocket-Key: (.*)").matcher(data);
                match.find();

                byte[] response = (
                        "HTTP/1.1 101 Switching Protocols\r\n" +
                        "Upgrade: websocket\r\n" +
                        "Connection: Upgrade\r\n" +
                        "Sec-WebSocket-Accept: " +
                        Base64.getEncoder().encodeToString(
                                MessageDigest.getInstance("SHA-1").digest(
                                        (match.group(1).trim() + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
                                                .getBytes("UTF-8")
                                )
                        ) + "\r\n\r\n"
                ).getBytes("UTF-8");

                out.write(response);
                out.flush();
                System.out.println("ESP32 WebSocket handshake complete");

                while (true) {
                    byte[] header = in.readNBytes(2);
                    if (header.length < 2) {
                        System.out.println("ESP32 connection closed");
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
                    // System.out.println("Received: " + message);

                    double hallValue = parseHallValue(message);
                    if (hallValue >= 0) {
                        double mag = calculateMagneticField(hallValue);
                        updateRpm(mag);

                        String json = String.format(
                                "{\"magneticField\":%.2f,\"rpm\":%.0f,\"peakRpm\":%.0f}",
                                mag, currentRpm, peakRpm
                        );
                        broadcastToBrowsers(json);
                    }
                }
            }
        } catch (IOException | NoSuchAlgorithmException e) {
            System.out.println("ESP32 client error: " + e.getMessage());
        }
    }

    static void updateRpm(double mag) {
        double absMag = Math.abs(mag);
        long now = System.currentTimeMillis();
        if (!aboveThreshold && absMag > peakThreshold) {
            aboveThreshold = true;
            if (lastPeakTime > 0) {
                double intervalMs = now - lastPeakTime;
                double instantRpm = 60_000.0 / intervalMs;

                rpmHistory[rpmIndex % rpmHistory.length] = instantRpm;
                rpmIndex++;
                if (rpmCount < rpmHistory.length) rpmCount++;
                double sum = 0;
                for (int i = 0; i < rpmCount; i++) sum += rpmHistory[i];
                currentRpm = sum / rpmCount;

                if (currentRpm > peakRpm) peakRpm = currentRpm;
            }
            lastPeakTime = now;
        } else if (aboveThreshold && absMag <= peakThreshold) {
            aboveThreshold = false;
        }

        System.out.printf("Mag: %.2f mT, RPM: %.0f, Peak RPM: %.0f%n", mag, currentRpm, peakRpm);
    }

    static void broadcastToBrowsers(String msg) {
        for (OutputStream out : browserClients) {
            try {
                sendMessage(out, msg);
            } catch (IOException e) {
                browserClients.remove(out);
            }
        }
    }

    static double parseHallValue(String json) {
        Pattern pattern = Pattern.compile("\"hall_mT\":([\\d.]+)");        
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            return Double.parseDouble(matcher.group(1));
        }
        return -1;
    }

    static double calculateMagneticField(double rawValue) {
        double voltage = (rawValue / 4095.0) * 5;
        double zeroLevel = 2.5; // max voltage (5)/2
        double sensitivity = 0.0025; // ** ADJUST THIS ** Note: 0.0025 seems to be working fine so far
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