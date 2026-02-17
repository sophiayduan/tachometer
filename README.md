# Tachometer
A DIY tachometer, made to determine RPM using a magnet + hall effect sensor combo. 

### Prerequistes 
- Arduino IDE with ESP32 board
- Arduino libraries: WebSocketsClient, ArduinoJson, ESP32Servo
- Java 11+ (for the WebSocket server)

### Hardware

ESP32 with a Hall effect sensor wired to pin 35, potentiometer on pin 34, ESC on pin 5

*The server machine and ESP32 must be on the same local network*


Flash ESP32 with `rawData.ino`, and input your **"YOUR_SSID"**,**"YOUR_PASSWORD"**, and your **IP_ADDRESS** (the device running the Java server)


Compile and run `WebSocket.java`
Port 8080 is used for the WebSocket server (ESP32)
Port 8081 is used for HTTP (web browser)

Open `index.html` using Live Server (VS Code extension or equivalent).

