#include "BeaconBle.h"

#include <BLEAdvertising.h>
#include <BLEDevice.h>
#include <BLEUtils.h>

#include <string>

#include "BeaconConfig.h"

namespace {

std::string manufacturerData() {
  // BLE manufacturer data begins with a 16-bit company ID in little-endian order.
  std::string data;
  data += static_cast<char>(BLE_MANUFACTURER_ID & 0xFF);
  data += static_cast<char>((BLE_MANUFACTURER_ID >> 8) & 0xFF);
  // Compact and stable identity payload: CP1|<classroom>|<ESP number>
  data += "CP1|";
  data += CLASSROOM_ID;
  data += "|";
  data += std::to_string(ESP_NUMBER);
  return data;
}

}  // namespace

namespace BeaconBle {

void begin() {
  BLEDevice::init(BLE_DEVICE_NAME);

  BLEAdvertising* advertising = BLEDevice::getAdvertising();

  BLEAdvertisementData advertisementData;
  advertisementData.setManufacturerData(manufacturerData().c_str());

  // A short UUID plus the manufacturer payload remains within the 31-byte BLE
  // advertising packet budget. The device name is sent in scan response data.
  advertising->setAdvertisementData(advertisementData);
  advertising->addServiceUUID(BLEUUID(static_cast<uint16_t>(BLE_SERVICE_UUID_SHORT)));

  BLEAdvertisementData scanResponseData;
  scanResponseData.setName(BLE_DEVICE_NAME);
  advertising->setScanResponseData(scanResponseData);
  advertising->setScanResponse(true);

  advertising->start();

  Serial.println("BLE advertising started.");
  printStatus();
}

void printStatus() {
  Serial.printf("  Device name: %s\n", BLE_DEVICE_NAME);
  Serial.printf("  Service UUID: 0x%04X\n", BLE_SERVICE_UUID_SHORT);
  Serial.printf("  Manufacturer payload: CP1|%s|%d\n", CLASSROOM_ID, ESP_NUMBER);
}

}  // namespace BeaconBle
