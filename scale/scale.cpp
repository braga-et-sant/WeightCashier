#include <SPI.h>
#include <Ethernet.h>
#include <PubSubClient.h>
#include <Wire.h>
#include <Adafruit_PN532.h>
#include <HX711.h>

// ==========================================
// 1. NETWORK SETTINGS FOR ROUTER + RASPBERRY PI
// ==========================================
byte mac[] = { 0xDE, 0xAD, 0xBE, 0xEF, 0xFE, 0x01 };

// Static IP for the Arduino
IPAddress ip(192, 168, 0, 10);

// Router address on the LAN
IPAddress dns(192, 168, 0, 1);
IPAddress gateway(192, 168, 0, 1);
IPAddress subnet(255, 255, 255, 0);

// Raspberry Pi IP where the MQTT broker runs
IPAddress server(192, 168, 0, 2);

const char* scale_id = "SCALE_01";
const char* topic_publish = "supermarket/scale/SCALE_01/reading";
const char* topic_command = "supermarket/scale/SCALE_01/command";
const char* topic_status = "supermarket/scale/SCALE_01/status";

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

float calibration_factor = 2280.0;
const float MIN_WEIGHT_GRAMS = 5.0;
const float MAX_WEIGHT_GRAMS = 30000.0;

bool isArmed = false;
bool isBusy = false;
unsigned long lastHeartbeatMillis = 0;
const unsigned long HEARTBEAT_INTERVAL_MS = 5000;

void publishStatus(const char* status, const char* reason = nullptr) {
  String payload = "{\"status\":\"" + String(status) + "\"";
  if (reason != nullptr) {
    payload += ",\"reason\":\"" + String(reason) + "\"";
  }
  payload += "}";
  mqttClient.publish(topic_status, payload.c_str());
}

void handleTareCommand() {
  if (isBusy || isArmed) {
    publishStatus("busy", "already_armed");
    return;
  }

  Serial.println("[CMD] Tare requested. Zeroing scale...");
  scale.tare();
  isArmed = true;
  isBusy = true;
  publishStatus("tared");
}

void onMqttMessage(char* topic, byte* payload, unsigned int length) {
  String topicStr = String(topic);
  String message = "";

  for (unsigned int i = 0; i < length; i++) {
    message += (char)payload[i];
  }

  if (topicStr == topic_command && message.indexOf("tare") >= 0) {
    handleTareCommand();
  }
}

void setup() {
  Serial.begin(115200);
  while (!Serial);

  Serial.println("\n=== LAN MODE: ARDUINO -> ROUTER -> RASPBERRY PI ===");

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
  Serial.println("Connecting Ethernet shield to the LAN...");
  Ethernet.begin(mac, ip, dns, gateway, subnet);
  delay(1000);

  Serial.print("[OK] Arduino IP: ");
  Serial.println(Ethernet.localIP());
  Serial.print("[OK] MQTT broker IP: ");
  Serial.println(server);

  Serial.print("[OK] Ethernet hardware: ");
  Serial.println(Ethernet.hardwareStatus() == EthernetNoHardware ? "not found" : "found");

  Serial.print("[OK] Ethernet link: ");
  EthernetLinkStatus linkStatus = Ethernet.linkStatus();
  if (linkStatus == LinkON) {
    Serial.println("up");
  } else if (linkStatus == LinkOFF) {
    Serial.println("down");
  } else {
    Serial.println("unknown");
  }

  // Configure MQTT client to connect to the Raspberry Pi broker
  mqttClient.setServer(server, 1883);
  mqttClient.setCallback(onMqttMessage);
}

void reconnectMQTT() {
  while (!mqttClient.connected()) {
    Serial.print("Trying to connect to the MQTT Broker on the Pi (");
    Serial.print(server);
    Serial.println(")...");

    if (mqttClient.connect("ArduinoScale1")) {
      Serial.println("[SUCCESS] Connected to the Raspberry Pi via the LAN!");
      mqttClient.subscribe(topic_command);
      publishStatus("alive");
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

  if (millis() - lastHeartbeatMillis >= HEARTBEAT_INTERVAL_MS) {
    lastHeartbeatMillis = millis();
    publishStatus("alive");
  }

  if (!isArmed) {
    return;
  }

  uint8_t success;
  uint8_t uid[] = { 0, 0, 0, 0, 0, 0, 0, 0 };
  uint8_t uidLength;

  success = nfc.readPassiveTargetID(
    PN532_MIFARE_ISO14443A,
    uid,
    &uidLength,
    100
  );

  if (success && uidLength > 0) {
    Serial.println("\n--- PRODUCT DETECTED ---");
    Serial.print("UID length: ");
    Serial.println(uidLength);

    String rfidStr = "";

    for (uint8_t i = 0; i < uidLength; i++) {
      Serial.print("UID[");
      Serial.print(i);
      Serial.print("]=0x");
      if (uid[i] < 0x10) {
        Serial.print('0');
      }
      Serial.println(uid[i], HEX);

      if (uid[i] <= 0x0F) rfidStr += "0";
      rfidStr += String(uid[i], HEX);
    }

    rfidStr.toUpperCase();

    if (rfidStr.length() == 0) {
      Serial.println("[ERROR] PN532 returned an empty RFID string.");
      publishStatus("error", "rfid_empty");
      isArmed = false;
      isBusy = false;
      return;
    }

    Serial.print("RFID: ");
    Serial.println(rfidStr);

    float weight = scale.get_units(5);
    if (weight < 0) weight = 0.0;

    if (weight < MIN_WEIGHT_GRAMS) {
      return;
    }

    if (weight > MAX_WEIGHT_GRAMS) {
      publishStatus("error", "weight_too_high");
      isArmed = false;
      isBusy = false;
      return;
    }

    Serial.print("Weight: ");
    Serial.print(weight, 1);
    Serial.println(" g");

    String payload =
      "{\"rfid\":\"" + rfidStr +
      "\",\"weight\":" + String(weight, 1) + "}";

    if (mqttClient.publish(topic_publish, payload.c_str())) {
      Serial.println("[MQTT] Successfully sent to the Raspberry Pi!");
    } else {
      Serial.println("[MQTT] Sending error.");
    }

    isArmed = false;
    isBusy = false;

    delay(2000);
  } else {
    if (success && uidLength == 0) {
      Serial.println("[ERROR] PN532 detected a card but returned zero UID length.");
      publishStatus("error", "rfid_empty");
    }
    publishStatus("error", "rfid_missing");
    isArmed = false;
    isBusy = false;
  }
}