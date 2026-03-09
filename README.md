# Tachometer

### A _greatly_ overcomplicated tachometer that determines RPM using a magnet and hall effect sensor, then outputs it to a live dashboard via Wi-Fi.
<img src="https://cdn.hackclub.com/019cd32f-7901-7e60-84c0-4892da624f1f/image.png" width="700px" height="auto">


### Prerequistes 
- Arduino IDE + ESP32 board (with WiFi capabilities)
- Arduino libraries: WebSocketsClient, ArduinoJson, WiFi
- Java 11+ (for the WebSocket server)

### Hardware

ESP32 with a Hall effect sensor wired to pin 35, potentiometer on pin 34, ESC on pin 5

*The server machine and ESP32 must be on the same local network*


Flash ESP32 with `rawData.ino`, and input your **"YOUR_SSID"**,**"YOUR_PASSWORD"**, and **IP_ADDRESS** (the device running the Java server)


Compile and run `WebSocket.java`
Port 8080 is used for the WebSocket server (ESP32)
Port 8081 is used for HTTP (web browser), the WebSocket aswell

Open `index.html` using Live Server (VS Code extension or equivalent).

> this was a school assignment, and therefore, a really awesome excuse to continue my [BLDC motor](https://github.com/sophiayduan/bldc-motor/tree/main) project
