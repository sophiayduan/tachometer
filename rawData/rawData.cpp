#include <WiFi.h>
#include <WebSocketsClient.h>
#include <ArduinoJson.h>

const char* ssid = "";
const char* password = "";

WebSocketsClient webSocket;

#define HALL_PIN 35
bool sent = false;


void setup() {
  Serial.begin(115200);
  WiFi.begin(ssid, password);
  while (WiFi.status() != WL_CONNECTED) { delay(500); }
  webSocket.begin("192.168.2.189", 8080, "/");
}

void loop() {
  webSocket.loop();

  JsonDocument doc;
  doc["analog"] = analogRead(0);
  doc["hall_mT"] = analogRead(HALL_PIN);
  String jsonString;
  serializeJson(doc, jsonString);
  webSocket.sendTXT(jsonString);

  delay(1000); // send every 10ms
}
