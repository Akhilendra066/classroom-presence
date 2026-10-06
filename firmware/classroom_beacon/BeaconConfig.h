#pragma once

/*
 * Edit ONLY this file before flashing each ESP32 board.
 *
 * Flash the sketch once with ESP_NUMBER = 1, label that physical board
 * CLASSROOM_ESP_1, then change ESP_NUMBER and WIFI_SSID for the next board.
 * All four boards must use a unique ESP_NUMBER and Wi-Fi SSID.
 */

// ----- Classroom and beacon identity -----
#define CLASSROOM_ID "ROOM_A101"
#define ESP_NUMBER 1
#define ESP_ID "CLASSROOM_ESP_1"
#define WIFI_SSID "CP_ROOM_A101_ESP_1"

// This is shown by BLE scanner applications as the device name.
#define BLE_DEVICE_NAME ESP_ID

// ----- Wi-Fi SoftAP beacon settings -----
// Empty password = open beacon network. The phone only scans it; it does not connect.
// If a password is used, it must contain at least 8 characters.
#define WIFI_PASSWORD ""
#define WIFI_CHANNEL 1
#define WIFI_HIDDEN false
#define WIFI_MAX_CLIENTS 1

// ----- BLE beacon settings -----
// A short service UUID keeps the BLE advertisement compact and compatible with ESP32-WROOM-32.
#define BLE_SERVICE_UUID_SHORT 0xCA01
// 0xFFFF is reserved for development/testing manufacturer data.
#define BLE_MANUFACTURER_ID 0xFFFF

// ----- Diagnostics -----
#define SERIAL_BAUD_RATE 115200
#define HEALTH_LOG_INTERVAL_MS 30000UL
