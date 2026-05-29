#include <SPI.h>
#include <Ethernet.h>
#include <PubSubClient.h>
#include <Wire.h>
#include <Adafruit_PN532.h>
#include <HX711.h>

// ==========================================
// 1. NETWORK CONFIGURATION
// ==========================================
// Different MAC and IP from SCALE_01 — both must be unique on the LAN
byte mac[] = { 0xDE, 0xAD, 0xBE, 0xEF, 0xFE, 0x02 };

IPAddress ip(192, 168, 0, 11);
IPAddress gateway(192, 168, 0, 2);
IPAddress server(192, 168, 0, 2);  // PC/server running MQTT broker

const char topic_publish[] PROGMEM = "supermarket/scale/SCALE_02/reading";
const char topic_command[] PROGMEM = "supermarket/scale/SCALE_02/command";
const char topic_status[]  PROGMEM = "supermarket/scale/SCALE_02/status";

unsigned long lastHeartbeatMillis = 0;
const unsigned long HEARTBEAT_INTERVAL_MS = 30000;

bool pendingTare = false;
unsigned long tareRequestedAt = 0;
const unsigned long TARE_TIMEOUT_MS = 8000;

EthernetClient ethClient;
PubSubClient mqttClient(ethClient);

// ==========================================
// 2. HARDWARE INTERFACES (NFC & SCALE)
// ==========================================
#define PN532_IRQ   (2)
#define PN532_RESET (3)
Adafruit_PN532 nfc(PN532_IRQ, PN532_RESET);

const int LOADCELL_DOUT_PIN = 4;
const int LOADCELL_SCK_PIN = 5;
HX711 scale;

float calibration_factor = 2280.0;  // Calibrate separately for this scale unit

bool mqttPublishP(const char* topicProgmem, const char* payload) {
  char topicBuf[48];
  strcpy_P(topicBuf, topicProgmem);
  return mqttClient.publish(topicBuf, payload);
}

void setup() {
  Serial.begin(115200);
  while (!Serial);

  Serial.println(F("\n=== SCALE_02 INITIALISING ==="));

  scale.begin(LOADCELL_DOUT_PIN, LOADCELL_SCK_PIN);
  scale.set_scale(calibration_factor);
  Serial.println(F("Zeroing scale..."));
  scale.tare();
  Serial.println(F("[OK] Scale zeroed."));

  nfc.begin();
  uint32_t versiondata = nfc.getFirmwareVersion();
  if (!versiondata) {
    Serial.println(F("[CRITICAL] PN532 not found!"));
    while (1);
  }
  nfc.SAMConfig();
  Serial.println(F("[OK] PN532 ready."));

  Serial.println(F("Starting Ethernet..."));
  Ethernet.begin(mac, ip, gateway);
  delay(1000);
  Serial.print(F("[OK] IP: "));
  Serial.println(Ethernet.localIP());

  mqttClient.setServer(server, 1883);
  mqttClient.setCallback(mqttCallback);
}

void mqttCallback(char* topic, byte* payload, unsigned int length) {
  if (length == 0) return;

  if (length >= 4 && memcmp(payload, "ping", 4) == 0) {
    Serial.println(F("[CMD] ping -> alive"));
    mqttPublishP(topic_status, "{\"status\":\"alive\"}");
  } else if (length >= 4 && memcmp(payload, "tare", 4) == 0) {
    Serial.println(F("[CMD] tare scheduled"));
    pendingTare = true;
    tareRequestedAt = millis();
  }
}

void processPendingTare() {
  if (!pendingTare) return;

  if (!scale.is_ready()) {
    if (millis() - tareRequestedAt > TARE_TIMEOUT_MS) {
      Serial.println(F("[ERROR] Tare timeout."));
      mqttPublishP(topic_status, "{\"status\":\"error\",\"reason\":\"timeout\"}");
      pendingTare = false;
    }
    return;
  }

  scale.tare();
  mqttPublishP(topic_status, "{\"status\":\"tared\"}");
  Serial.println(F("[OK] Tare done."));
  pendingTare = false;
}

void reconnectMQTT() {
  while (!mqttClient.connected()) {
    Serial.print(F("Connecting MQTT... "));
    // Unique client ID — must differ from SCALE_01's "ArduinoScale_01"
    if (mqttClient.connect("ArduinoScale_02")) {
      Serial.println(F("connected."));
      char topicBuf[48];
      strcpy_P(topicBuf, topic_command);
      mqttClient.subscribe(topicBuf, 1);
      mqttPublishP(topic_status, "{\"status\":\"alive\"}");
      lastHeartbeatMillis = millis();
    } else {
      Serial.print(F("[MQTT FAULT] rc="));
      Serial.print(mqttClient.state());
      Serial.println(F(". Retry 3s..."));
      delay(3000);
    }
  }
}

void loop() {
  if (!mqttClient.connected()) {
    reconnectMQTT();
  }

  mqttClient.loop();
  processPendingTare();

  if (millis() - lastHeartbeatMillis >= HEARTBEAT_INTERVAL_MS) {
    mqttPublishP(topic_status, "{\"status\":\"alive\"}");
    lastHeartbeatMillis = millis();
  }

  uint8_t uid[8] = {0};
  uint8_t uidLength;

  bool success = nfc.readPassiveTargetID(PN532_MIFARE_ISO14443A, uid, &uidLength, 80);

  if (success) {
    Serial.println(F("\n--- TAG DETECTED ---"));

    char rfidHex[17] = {0};
    for (uint8_t i = 0; i < uidLength; i++) {
      sprintf(&rfidHex[i * 2], "%02X", uid[i]);
    }
    Serial.print(F("RFID: "));
    Serial.println(rfidHex);

    float weight = scale.get_units(5);
    if (weight < 0) weight = 0.0;
    Serial.print(F("Weight: "));
    Serial.print(weight, 1);
    Serial.println(F(" g"));

    char weightBuf[10];
    char jsonPayload[64];
    dtostrf(weight, 4, 1, weightBuf);
    snprintf(jsonPayload, sizeof(jsonPayload), "{\"rfid\":\"%s\",\"weight\":%s}", rfidHex, weightBuf);

    if (mqttPublishP(topic_publish, jsonPayload)) {
      Serial.println(F("[OK] Reading published."));
    } else {
      Serial.println(F("[ERROR] Publish failed."));
    }

    delay(2000);
  }
}
