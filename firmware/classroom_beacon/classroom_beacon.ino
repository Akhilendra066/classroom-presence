/*
 * Classroom Presence Beacon for ESP32-WROOM-32
 *
 * The board continuously broadcasts:
 *   1. A unique Wi-Fi SoftAP SSID.
 *   2. A unique BLE advertisement and manufacturer payload.
 *
 * The mobile phone scans these broadcasts and measures RSSI. This firmware
 * does not collect student data and does not make attendance decisions.
 */

#include "BeaconBle.h"
#include "BeaconConfig.h"
#include "BeaconHealth.h"
#include "BeaconWifi.h"

unsigned long lastHealthLogMs = 0;

void setup() {
  Serial.begin(SERIAL_BAUD_RATE);
  delay(300);

  BeaconHealth::printBootBanner();
  BeaconWifi::begin();
  BeaconBle::begin();

  lastHealthLogMs = millis();
  Serial.println("Beacon ready: Wi-Fi and BLE are now advertising.");
}

void loop() {
  const unsigned long now = millis();
  if (now - lastHealthLogMs >= HEALTH_LOG_INTERVAL_MS) {
    lastHealthLogMs = now;
    BeaconHealth::printPeriodicStatus();
  }

  delay(50);
}
