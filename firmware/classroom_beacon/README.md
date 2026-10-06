# ESP32 Classroom Beacon (Arduino IDE)

This folder is a standalone Arduino IDE sketch for one ESP32-WROOM-32 beacon. It advertises a Wi-Fi SoftAP and BLE identity continuously after flashing. It has no Firebase, mobile-app, or attendance logic.

## Before flashing

1. In Arduino IDE, install **esp32 by Espressif Systems** from Boards Manager.
2. Open `classroom_beacon.ino` from this exact folder. Arduino IDE will load the `.cpp` and `.h` files beside it.
3. In `BeaconConfig.h`, set the identity for the physical board being flashed.
4. Select **Tools → Board → ESP32 Arduino → ESP32 Dev Module** for a typical ESP32-WROOM-32 development board.
5. Select its COM port, then upload.
6. Open Serial Monitor at **115200 baud**.

## Flashing all four boards

Flash one board at a time. Change these four values in `BeaconConfig.h` before each flash:

| Board | `ESP_NUMBER` | `ESP_ID` | `WIFI_SSID` |
| --- | ---: | --- | --- |
| Corner 1 | `1` | `CLASSROOM_ESP_1` | `CP_ROOM_A101_ESP_1` |
| Corner 2 | `2` | `CLASSROOM_ESP_2` | `CP_ROOM_A101_ESP_2` |
| Corner 3 | `3` | `CLASSROOM_ESP_3` | `CP_ROOM_A101_ESP_3` |
| Corner 4 | `4` | `CLASSROOM_ESP_4` | `CP_ROOM_A101_ESP_4` |

Keep `CLASSROOM_ID` the same for all beacons in one room. Attach a physical label matching `ESP_ID` immediately after flashing.

## Manual verification using Android scanner apps

Use a Wi-Fi analyzer/scanner and a BLE scanner (for example, nRF Connect) to check each board.

Expected Wi-Fi result:

```text
SSID: CP_ROOM_A101_ESP_1
RSSI: <a changing negative dBm value>
Channel: 1
```

Expected BLE result:

```text
Name: CLASSROOM_ESP_1
Service UUID: 0xCA01
Manufacturer data: CP1|ROOM_A101|1
RSSI: <a changing negative dBm value>
```

The exact RSSI changes with phone position, orientation, walls, people, and interference. This is expected.

## Acceptance checklist

- [ ] Wi-Fi scanner sees the unique SSID.
- [ ] BLE scanner sees the matching device name and manufacturer payload.
- [ ] Both appear while the board is powered for at least 30 minutes.
- [ ] All four boards are visible at the same time when installed in their planned positions.
- [ ] Serial Monitor prints Wi-Fi status and periodic health output without errors.

## Notes

- The open SoftAP is intentional for the first hardware test: the phone only scans its beacon and does not connect. Do not expose this board to an untrusted network as a data service.
- Wi-Fi and BLE share the ESP32-WROOM-32's 2.4 GHz radio. RSSI fluctuations and occasional variation are normal; later mobile logic handles this with filtering and temporal rules.
- Keep advertising identity values short. BLE advertising packets have limited space.
