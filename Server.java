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

    // prevents retriggers
    static double risingThreshold = 60;   // TUNE THIS — approx. half of max signal
    static double fallingThreshold = 20;  // must drop below this before next peak is counted
    static long MIN_PEAK_INTERVAL_MS = (long)(60_000.0 / 50000); // derived from RPM_MAX

    // volatile so the timeout thread always sees up to date values from the ESP32 
    static volatile boolean aboveThreshold = false;
    static volatile long lastPeakTime = -1;
    static volatile double currentRpm = 0;
    static volatile double peakRpm = 0;

    static volatile double lastMag = 0;

  
    static volatile double currentPulseMax = 0;
    static volatile long currentPulseMaxTime = -1;

    
    static final int RPM_HISTORY_LEN = 2; // smaller = faster response to speed changes like deceleration
    static double[] rpmHistory = new double[RPM_HISTORY_LEN];
    static int rpmIndex = 0;
    static int rpmCount = 0;
    static long RPM_TIMEOUT_MS = 3000; // give slow deceleration time 

    // limits for reasonable spm
    static double RPM_MIN = 10;
    static double RPM_MAX = 10000; 


    static volatile long lastBroadcastTime = 0;
    static final long BROADCAST_INTERVAL_MS = 50; // 20fps to browser, more than enough for display

    public static void main(String[] args) throws IOException {

        // resets RPM to 0 if no peak detected recently
        new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(100);
                    long now = System.nanoTime() / 1_000_000;
                    if (lastPeakTime > 0 && (now - lastPeakTime) > RPM_TIMEOUT_MS) {
                        currentRpm = 0;
                        rpmCount = 0;
                        java.util.Arrays.fill(rpmHistory, 0);
                        String json = String.format(
                            "{\"magneticField\":%.2f,\"rpm\":%.0f,\"peakRpm\":%.0f}",
                            lastMag, currentRpm, peakRpm
                        );
                        broadcastToBrowsers(json);
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }).start();

        // Browser WebSocket server on port 8081
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

        // ESP32 WebSocket server on port 8080
        ServerSocket esp32Server = new ServerSocket(8080);
        try {
            System.out.println("Waiting for connection on port 8080...");
            while (true) {
                Socket client = esp32Server.accept();
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

            Matcher wsMatcher = Pattern.compile("Upgrade: websocket", Pattern.CASE_INSENSITIVE).matcher(data);
            Matcher keyMatcher = Pattern.compile("Sec-WebSocket-Key: (.*)").matcher(data);

            if (wsMatcher.find() && keyMatcher.find()) {
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

                try {
                    while (true) {
                        if (in.read() == -1) break;
                    }
                } finally {
                    browserClients.remove(out);
                    System.out.println("Browser disconnected");
                }

            } else {
                String path = "site/index.html";
                if (data.contains("GET /script.js")) {
                    path = "site/script.js";
                }

                byte[] body = Files.readAllBytes(Paths.get(path));
                String contentType = path.endsWith(".js") ? "application/javascript" : "text/html";

                String response =
                        "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: " + contentType + "\r\n" +
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

                    int payloadLen = header[1] & 0x7F;

                    if (payloadLen == 126) {
                        byte[] ext = in.readNBytes(2);
                        payloadLen = ((ext[0] & 0xFF) << 8) | (ext[1] & 0xFF);
                    } else if (payloadLen == 127) {
                        byte[] ext = in.readNBytes(8);
                        payloadLen = ((ext[4] & 0xFF) << 24) | ((ext[5] & 0xFF) << 16) |
                                     ((ext[6] & 0xFF) << 8)  |  (ext[7] & 0xFF);
                    }

                    byte[] maskKey = in.readNBytes(4);
                    byte[] encoded = in.readNBytes(payloadLen);
                    byte[] decoded = new byte[payloadLen];

                    for (int i = 0; i < payloadLen; i++) {
                        decoded[i] = (byte) (encoded[i] ^ maskKey[i & 0x3]);
                    }

                    String message = new String(decoded, "UTF-8");

                    double hallValue = parseHallValue(message);
                    if (hallValue >= 0) {
                        double mag = calculateMagneticField(hallValue);
                        lastMag = mag; 
                        updateRpm(mag); 

                        // Only push to browser at 20fps, not constant like esp32
                        long nowMs = System.nanoTime() / 1_000_000;
                        if (nowMs - lastBroadcastTime >= BROADCAST_INTERVAL_MS) {
                            lastBroadcastTime = nowMs;


                            double displayRpm = currentRpm;
                            if (lastPeakTime > 0) {
                                long msSinceLastPeak = nowMs - lastPeakTime;
                                double impliedMaxRpm = 60_000.0 / msSinceLastPeak;
                                if (impliedMaxRpm < displayRpm) {
                                    displayRpm = impliedMaxRpm;
                                }
                            }

                            String json = String.format(
                                    "{\"magneticField\":%.2f,\"rpm\":%.0f,\"peakRpm\":%.0f}",
                                    mag, displayRpm, peakRpm
                            );
                            broadcastToBrowsers(json);
                        }
                    }
                }
            }
        } catch (IOException | NoSuchAlgorithmException e) {
            System.out.println("ESP32 client error: " + e.getMessage());
        }
    }

    static void updateRpm(double mag) {
        long now = System.nanoTime() / 1_000_000;

        if (!aboveThreshold && mag > risingThreshold) {
            // Rising edge= start tracking this pulse
            aboveThreshold = true;
            currentPulseMax = mag;
            currentPulseMaxTime = now;

        } else if (aboveThreshold && mag > currentPulseMax) {
            // if it it still increasing
            currentPulseMax = mag;
            currentPulseMaxTime = now;

        } else if (aboveThreshold && mag < fallingThreshold) {
            aboveThreshold = false;

            if (lastPeakTime > 0 && currentPulseMaxTime > 0) {
                double intervalMs = currentPulseMaxTime - lastPeakTime;

                // reject impossible readings/intervals
                if (intervalMs < MIN_PEAK_INTERVAL_MS) {
                    lastPeakTime = currentPulseMaxTime;
                    currentPulseMax = 0;
                    currentPulseMaxTime = -1;
                    return;
                }

                double instantRpm = 60_000.0 / intervalMs;

                // Discard readings outside a reasonable RPM range
                if (instantRpm >= RPM_MIN && instantRpm <= RPM_MAX) {
                    rpmHistory[rpmIndex % RPM_HISTORY_LEN] = instantRpm;
                    rpmIndex++;
                    if (rpmCount < RPM_HISTORY_LEN) rpmCount++;

                    // it averages the values, but it is WEIGHTED (to prioritize recent values)
                    double weightedSum = 0;
                    double totalWeight = 0;
                    for (int i = 0; i < rpmCount; i++) {
                        int age = (rpmCount - 1 - i); 
                        int bufIdx = ((rpmIndex - 1 - i) % RPM_HISTORY_LEN + RPM_HISTORY_LEN) % RPM_HISTORY_LEN;
                        double weight = (rpmCount - age); 
                        weightedSum += rpmHistory[bufIdx] * weight;
                        totalWeight += weight;
                    }
                    currentRpm = weightedSum / totalWeight;

                    if (currentRpm > peakRpm) peakRpm = currentRpm;
                }
            }

            lastPeakTime = currentPulseMaxTime;
            currentPulseMax = 0;
            currentPulseMaxTime = -1;
        }

        long nowMs = System.nanoTime() / 1_000_000;
        if (nowMs - lastBroadcastTime < 5) {
            System.out.printf("Mag: %.2f mT, RPM: %.0f, Peak RPM: %.0f%n", mag, currentRpm, peakRpm);
        }
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
        double zeroLevel = 2.5;
        double sensitivity = 0.0025; // ADJUST THIS if needed
        double gauss = (voltage - zeroLevel) / sensitivity;
        return gauss * 0.1; // Gauss to milliTesla
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