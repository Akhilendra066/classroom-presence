#include "BeaconHealth.h"

#include <Arduino.h>
#include <ESP.h>

#include "BeaconConfig.h"
#include "BeaconWifi.h"

namespace BeaconHealth {

void printBootBanner() {
  Serial.println();
  Serial.println("========================================");
  Serial.println(" Classroom Presence ESP Beacon");
  Serial.println("========================================");
  Serial.printf("Classroom: %s\n", CLASSROOM_ID);
  Serial.printf("ESP ID: %s\n", ESP_ID);
  Serial.printf("Chip model: %s\n", ESP.getChipModel());
  Serial.printf("Chip revision: %d\n", ESP.getChipRevision());
}

void printPeriodicStatus() {
  Serial.println("--- Beacon health ---");
  Serial.printf("Uptime: %lu s\n", millis() / 1000UL);
  Serial.printf("Free heap: %u bytes\n", ESP.getFreeHeap());
  BeaconWifi::printStatus();
}

}  // namespace BeaconHealth
