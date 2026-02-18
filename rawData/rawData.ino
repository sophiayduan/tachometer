// note: this is not the code I personally run in the demo, my script includes code for controlling my brushless DC motor
// for my sake, I have added all the servo tester code (part that controls BLDC motor), but have commented it out

// below are the libraries required, along with their author

// #include <ESP32Servo.h> // ESP32Servo - Kevin Harrington
#include <WiFi.h> // WiFi - Arduino
#include <WebSocketsClient.h> // WebSockets - Markus Sattler 
#include <ArduinoJson.h> // ArduinoJson - Benoit Blanchon


// #define POT_PIN 34       
// #define ESC_PIN 5
// #define ESC_MIN 1000
// #define ESC_MAX 2000
// #define SMOOTH_STEP 5

// Servo myESC;
// long currentPulse = ESC_MIN;

const char* ssid = "YOUR_SSID";
const char* password = "YOUR_PASSWORD";

WebSocketsClient webSocket;

#define HALL_PIN 35
bool sent = false;


void setup() {
  Serial.begin(115200);
  // myESC.attach(ESC_PIN, ESC_MIN, ESC_MAX);
  // myESC.writeMicroseconds(currentPulse);
  WiFi.begin(ssid, password);
  while (WiFi.status() != WL_CONNECTED) { delay(500); }
  webSocket.begin("YOUR_IP_ADDRESS", 8080, "/");
}

void loop() {
  webSocket.loop();

  JsonDocument doc;
  doc["analog"] = analogRead(0);
  doc["hall_mT"] = analogRead(HALL_PIN);
  String jsonString;
  serializeJson(doc, jsonString);
  webSocket.sendTXT(jsonString);


  // int potValue = analogRead(POT_PIN); 
  // long targetPulse = map(potValue, 0, 4095, ESC_MIN, ESC_MAX);

  // if (currentPulse < targetPulse) {
  //   currentPulse += SMOOTH_STEP;
  //   if (currentPulse > targetPulse) currentPulse = targetPulse;
  // } else if (currentPulse > targetPulse) {
  //   currentPulse -= SMOOTH_STEP;
  //   if (currentPulse < targetPulse) currentPulse = targetPulse;
  // }

  // myESC.writeMicroseconds(currentPulse);

  delay(100); 
}
  