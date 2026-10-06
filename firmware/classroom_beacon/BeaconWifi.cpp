#include "BeaconWifi.h"

#include <cstring>

#include <WiFi.h>

#include "BeaconConfig.h"

namespace BeaconWifi {

void begin() {
  WiFi.persistent(false);
  WiFi.mode(WIFI_AP);

  const char* password = (strlen(WIFI_PASSWORD) == 0) ? nullptr : WIFI_PASSWORD;
  const bool started = WiFi.softAP(
      WIFI_SSID,
      password,
      WIFI_CHANNEL,
      WIFI_HIDDEN,
      WIFI_MAX_CLIENTS);

  if (!started) {
    Serial.println("ERROR: Wi-Fi SoftAP could not start.");
    return;
  }

  Serial.println("Wi-Fi SoftAP started.");
  printStatus();
}

void printStatus() {
  Serial.printf("  SSID: %s\n", WIFI_SSID);
  Serial.printf("  Channel: %d\n", WIFI_CHANNEL);
  Serial.printf("  AP IP: %s\n", WiFi.softAPIP().toString().c_str());
  Serial.printf("  Connected clients: %d\n", WiFi.softAPgetStationNum());
}

}  // namespace BeaconWifi
