#include <SPI.h>
#include <Ethernet.h>
#include <ArduinoMqttClient.h>
#include <Wire.h>
#include <Adafruit_PN532.h>
#include <HX711.h>

// ==========================================
// 1. NETWORK SETTINGS FOR DIRECT CONNECTION (PC <-> ARDUINO)
// ==========================================
byte mac[] = { 0xDE, 0xAD, 0xBE, 0xEF, 0xFE, 0x01 }; 

// Static IP for the Arduino
IPAddress ip(192, 168, 0, 10); 

// IP configured on the PC Ethernet adapter
IPAddress server(192, 168, 0, 2); 

const char* scale_id = "SCALE_01";
const char* topic_publish = "supermarket/scale/SCALE_01/reading";
const char* topic_command = "supermarket/scale/SCALE_01/command";
const char* topic_status = "supermarket/scale/SCALE_01/status";

EthernetClient ethClient;
MqttClient mqttClient(ethClient);

// ==========================================
// 2. NFC SETTINGS (Via I2C)
// ==========================================
#define PN532_IRQ   (2)
#define PN532_RESET (3)
Adafruit_PN532 nfc(PN532_IRQ, PN532_RESET);

// ==========================================
// 3. SCALE SETTINGS (HX711)
// ==========================================
const int LOADCELL_DOUT_PIN = 4;
const int LOADCELL_SCK_PIN = 5;
HX711 scale;

float calibration_factor = 2280.0; // Change after calibration if needed
const float MIN_WEIGHT_GRAMS = 5.0;
const float MIN_DELTA_GRAMS = 3.0;
const float MAX_WEIGHT_GRAMS = 30000.0;

bool isArmed = false;
bool isBusy = false;
float lastWeight = 0.0;

unsigned long lastHeartbeatMillis = 0;
const unsigned long HEARTBEAT_INTERVAL_MS = 5000;

void publishStatus(const char* status, const char* reason = nullptr) {
  String payload = "{\"status\":\"" + String(status) + "\"";
  if (reason != nullptr) {
    payload += ",\"reason\":\"" + String(reason) + "\"";
  }
  payload += "}";

  mqttClient.beginMessage(topic_status, payload.length(), false, 1);
  mqttClient.print(payload);
  mqttClient.endMessage();
}

void handleTareCommand() {
  if (isBusy || isArmed) {
    publishStatus("busy", "already_armed");
    return;
  }

  Serial.println("[CMD] Tare requested. Zeroing scale...");
  scale.tare();
  lastWeight = scale.get_units(3);
  isArmed = true;
  isBusy = true;
  publishStatus("tared");
}

void onMqttMessage(int messageSize) {
  String topic = mqttClient.messageTopic();
  String payload = "";
  while (mqttClient.available()) {
    payload += (char)mqttClient.read();
  }

  if (topic == topic_command && payload.indexOf("tare") >= 0) {
    handleTareCommand();
  }
}

void setup() {
  Serial.begin(115200);
  while (!Serial); 

  Serial.println("\n=== DIRECT CONNECTION MODE: ARDUINO <-> PC ===");

  // Initialize Scale (HX711)
  scale.begin(LOADCELL_DOUT_PIN, LOADCELL_SCK_PIN);
  scale.set_scale(calibration_factor);

  Serial.println("Taring the scale... Make sure there is no weight on it.");
  scale.tare(); 
  Serial.println("[OK] Scale zeroed.");

  // Initialize NFC (PN532)
  nfc.begin();

  uint32_t versiondata = nfc.getFirmwareVersion();

  if (!versiondata) {
    Serial.println("[ERROR] PN532 board not found!");
    while (1); 
  }

  nfc.SAMConfig(); 
  Serial.println("[OK] NFC reader ready.");

  // Initialize Ethernet Network with Static IP
  Serial.println("Connecting Ethernet shield directly to the PC...");
  Ethernet.begin(mac, ip);

  delay(1000); 
  
  Serial.print("[OK] Arduino IP: ");
  Serial.println(Ethernet.localIP());

  // Configure MQTT Client to connect to the PC
  mqttClient.setId("ArduinoScale1");
  mqttClient.onMessage(onMqttMessage);
}

void reconnectMQTT() {
  while (!mqttClient.connected()) {

    Serial.print("Trying to connect to the MQTT Broker on the PC (");
    Serial.print(server);
    Serial.println(")...");

    if (mqttClient.connect(server, 1883)) {

      Serial.println("[SUCCESS] Connected to the PC via Direct Cable!");
      mqttClient.subscribe(topic_command, 1);

    } else {

      Serial.print("[ERROR] Code=");
      Serial.print(mqttClient.connectError());
      Serial.println(" -> Retrying in 3 seconds.");

      delay(3000);
    }
  }
}

void loop() {

  if (!mqttClient.connected()) {
    reconnectMQTT();
  }

  mqttClient.poll();

  if (millis() - lastHeartbeatMillis >= HEARTBEAT_INTERVAL_MS) {
    lastHeartbeatMillis = millis();
    publishStatus("alive");
  }

  if (!isArmed) {
    return;
  }

  float weight = scale.get_units(3);
  if (weight < 0) weight = 0.0;

  if (weight > MAX_WEIGHT_GRAMS) {
    publishStatus("error", "weight_too_high");
    isArmed = false;
    isBusy = false;
    return;
  }

  if (weight < MIN_WEIGHT_GRAMS) {
    return;
  }

  if (abs(weight - lastWeight) < MIN_DELTA_GRAMS) {
    return;
  }

  uint8_t success;
  uint8_t uid[] = { 0, 0, 0, 0, 0, 0, 0, 0 };
  uint8_t uidLength;

  success = nfc.readPassiveTargetID(
    PN532_MIFARE_ISO14443A,
    uid,
    &uidLength,
    200
  );

  if (!success) {
    publishStatus("error", "rfid_missing");
    isArmed = false;
    isBusy = false;
    return;
  }

  Serial.println("\n--- PRODUCT DETECTED ---");

  String rfidStr = "";

  for (uint8_t i = 0; i < uidLength; i++) {
    if (uid[i] <= 0x0F) rfidStr += "0";
    rfidStr += String(uid[i], HEX);
  }

  rfidStr.toUpperCase();

  Serial.print("RFID: ");
  Serial.println(rfidStr);

  Serial.print("Weight: ");
  Serial.print(weight, 1);
  Serial.println(" g");

  String payload =
    "{\"rfid\":\"" + rfidStr +
    "\",\"weight\":" + String(weight, 1) + "}";

  mqttClient.beginMessage(topic_publish, payload.length(), true, 1);
  mqttClient.print(payload);
  if (mqttClient.endMessage()) {

    Serial.println("[MQTT] Successfully sent to the PC!");

  } else {

    Serial.println("[MQTT] Sending error.");
  }

  lastWeight = weight;
  isArmed = false;
  isBusy = false;
  delay(500);
}