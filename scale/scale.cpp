#include <SPI.h>
#include <Ethernet.h>
#include <PubSubClient.h>
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

const char* topic_publish = "supermarket/scale/1/reading";

EthernetClient ethClient;
PubSubClient mqttClient(ethClient);

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
  mqttClient.setServer(server, 1883);
}

void reconnectMQTT() {
  while (!mqttClient.connected()) {

    Serial.print("Trying to connect to the MQTT Broker on the PC (");
    Serial.print(server);
    Serial.println(")...");

    if (mqttClient.connect("ArduinoScale1")) {

      Serial.println("[SUCCESS] Connected to the PC via Direct Cable!");

    } else {

      Serial.print("[ERROR] Code=");
      Serial.print(mqttClient.state());
      Serial.println(" -> Retrying in 3 seconds.");

      delay(3000);
    }
  }
}

void loop() {

  if (!mqttClient.connected()) {
    reconnectMQTT();
  }

  mqttClient.loop();

  uint8_t success;
  uint8_t uid[] = { 0, 0, 0, 0, 0, 0, 0, 0 };
  uint8_t uidLength;

  success = nfc.readPassiveTargetID(
    PN532_MIFARE_ISO14443A,
    uid,
    &uidLength,
    100
  );

  if (success) {

    Serial.println("\n--- PRODUCT DETECTED ---");

    String rfidStr = "";

    for (uint8_t i = 0; i < uidLength; i++) {
      if (uid[i] <= 0x0F) rfidStr += "0";
      rfidStr += String(uid[i], HEX);
    }

    rfidStr.toUpperCase();

    Serial.print("RFID: ");
    Serial.println(rfidStr);

    float weight = scale.get_units(5);

    if (weight < 0) weight = 0.0;

    Serial.print("Weight: ");
    Serial.print(weight, 1);
    Serial.println(" g");

    String payload =
      "{\"rfid\":\"" + rfidStr +
      "\",\"weight\":" + String(weight, 1) + "}";

    if (mqttClient.publish(topic_publish, payload.c_str())) {

      Serial.println("[MQTT] Successfully sent to the PC!");

    } else {

      Serial.println("[MQTT] Sending error.");
    }

    delay(2000);
  }
}