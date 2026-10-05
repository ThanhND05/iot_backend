# Tổng hợp luồng thực thi — Hệ thống IoT

## Kiến trúc tổng quan

```
ESP8266 ←──── MQTT Broker (port 1884) ────→ Spring Boot Backend (port 8080)
                                                      │
                                          ┌───────────┴───────────┐
                                          │                       │
                                        MySQL              WebSocket (STOMP/SockJS)
                                          │                       │
                                          └───────────┬───────────┘
                                                      │
                                            React Frontend (port 5173)
```

---

## 1. Luồng nhận dữ liệu cảm biến (ESP → DB → Dashboard)

```
ESP8266
  │  publish MQTT topic: iot/sensors/data
  │  payload: { "temperature": 30.6, "humidity": 73.6, "light": 1009 }
  ▼
MQTT Broker (port 1884)
  │
  ▼
MqttConfig.inbound() — subscribe topic: iot/sensors/data
  │
  ▼
MqttMessageHandler.handleMessage()
  │  → nhận ra topic "iot/sensors/data"
  │
  ▼
handleSensorData(JsonNode)
  ├─ parse temperature, humidity, light, timestamp
  ├─ saveSensorData("temperature", value, timestamp) → INSERT datasensors
  ├─ saveSensorData("humidity",    value, timestamp) → INSERT datasensors
  ├─ saveSensorData("light",       value, timestamp) → INSERT datasensors
  └─ messagingTemplate.convertAndSend("/topic/sensors", LatestDataSensorResponse)
                                                              │
                                                              ▼
                                                    WebSocket STOMP Broker
                                                              │
                                                              ▼
                                            websocketService.onSensorData(callback)
                                                              │
                                              ┌───────────────┴───────────────┐
                                              ▼                               ▼
                                    Dashboard.tsx                    DataSensor.tsx
                                    setSensors(newData)          (không dùng WS,
                                    setChartData(append)          chỉ fetch khi load)
```

---

## 2. Luồng bật/tắt đèn LED (Frontend → MQTT → ESP → DB → Frontend)

```
User nhấn toggle LED trên Dashboard.tsx
  │
  ▼
handleToggleDevice(deviceId)
  ├─ optimistic update: setLeds() ngay lập tức (UI phản hồi nhanh)
  ├─ setLoadingLeds[deviceId] = true
  │
  ▼
deviceApi.performAction(deviceId, 'ON'|'OFF', userId=1)
  │  POST /api/devices/{deviceId}/actions
  │  body: { userId: 1, action: "ON" }
  ▼
DeviceController.performAction()
  │
  ▼
ActionServiceImpl.performAction()
  ├─ INSERT actions (status = "PENDING") → MySQL
  ├─ mqttOutboundChannel.send() → publish topic: iot/devices/{id}/action
  │    payload: { "actionId": X, "deviceId": Y, "action": "ON" }
  ├─ scheduler.schedule(checkTimeout, delay=15s)  ← timeout 15 giây
  └─ return ActionResponse(status=PENDING)
                │
                ▼
  API trả về success=true → setLoadingLeds[deviceId] = false (nếu API OK)

  ── Đồng thời, ESP nhận lệnh qua MQTT ──
  ▼
ESP8266
  │  subscribe topic: iot/devices/{id}/action
  │  thực thi bật/tắt LED thật
  │  publish topic: iot/devices/{id}/status
  │  payload: { "actionId": X, "deviceId": Y, "status": "SUCCESS" }
  ▼
MqttConfig.inbound() — subscribe: iot/devices/+/status
  │
  ▼
MqttMessageHandler.handleActionStatus()
  │
  ▼
ActionServiceImpl.updateActionStatus(actionId, deviceId, "SUCCESS")
  ├─ UPDATE actions SET status="SUCCESS" → MySQL
  └─ messagingTemplate.convertAndSend("/topic/devices/{id}/status", ActionResponse)
                                                              │
                                                              ▼
                                                    WebSocket STOMP Broker
                                                              │
                                         ┌────────────────────┴────────────────────┐
                                         ▼                                         ▼
                               Dashboard.tsx                               History.tsx
                          onDeviceStatus callback                     onDeviceStatus callback
                          statusData.status == "SUCCESS"              setTimeout 500ms
                          → setLeds[deviceId] = true/false           → fetchData() (reload bảng)
                          → setLoadingLeds[deviceId] = false

  ── Nếu ESP không phản hồi trong 15 giây ──
  scheduler fires checkTimeout()
    → nếu action vẫn PENDING → UPDATE status = "FAILED"
    → messagingTemplate.send("/topic/devices/{id}/status", ActionResponse(FAILED))
    → Dashboard rollback LED state
    → History refresh hiện FAILED
```

---

## 3. Luồng xem lịch sử hành động (History page)

```
User mở trang History / thay đổi filter
  │
  ▼
History.tsx — fetchData()
  │  GET /api/actions?page=X&size=Y&device=...&action=...&status=...&time=...
  ▼
ActionController.searchActions()
  │
  ▼
ActionServiceImpl.searchActions()
  ├─ buildSearchSpecification() → JPA Specification
  │   ├─ filter: device.name = ?
  │   ├─ filter: action = ON/OFF
  │   ├─ filter: status = PENDING/SUCCESS/FAILED
  │   └─ buildTimePredicate(time) — parse các định dạng:
  │       "29"              → DAY(createdAt) = 29
  │       "2026"            → YEAR(createdAt) = 2026
  │       "29/09"           → DAY=29 AND MONTH=9
  │       "09/2026"         → trong tháng 9/2026
  │       "29/09/2026"      → trong ngày 29/09/2026
  │       "29/09/2026 15"   → trong giờ 15:xx ngày 29/09/2026
  │       "29/09/2026 15:58"→ trong phút 15:58 ngày đó
  │       "15:30"           → HOUR=15 AND MINUTE=30 (mọi ngày)
  └─ Page<Action> → PageResponse<ActionResponse>
  │
  ▼
History.tsx render bảng (giữ data cũ trong khi loading, spinner nhỏ trên toolbar)
  │
  ▼ (realtime)
WebSocket onDeviceStatus → setTimeout 500ms → fetchData() tự động
```

---

## 4. Luồng xem dữ liệu cảm biến (DataSensor page)

```
User mở trang DataSensor / thay đổi filter / search
  │
  ▼
DataSensor.tsx — fetchData()
  │  GET /api/datasensors?page=X&size=Y&type=temperature&search=30&searchMode=ALL
  ▼
DataSensorController.searchDataSensors()
  │
  ▼
DataSensorServiceImpl.searchDataSensors()
  ├─ filter: sensor.name = "temperature" | "humidity" | "light" | All
  ├─ searchMode = "TIME"  → chỉ tìm theo buildTimePredicate(search)
  └─ searchMode = "ALL"   → tìm theo value LIKE 'search%'
                             OR buildTimePredicate(search)
  │
  ▼
DataSensor.tsx render bảng (spinner inline, không overlay)
  │
  ▼ (KHÔNG có WebSocket — chỉ fetch khi load/filter thay đổi)
```

---

## 5. Luồng Dashboard — Load ban đầu

```
Dashboard.tsx mount
  │
  ├─ sensorApi.getLatest()       → GET /api/datasensors/latest
  │   → lấy nhiệt độ, độ ẩm, ánh sáng mới nhất → hiện StatCard
  │
  ├─ sensorApi.getChartData()    → GET /api/datasensors/chart/latest
  │   → lấy 10 điểm gần nhất mỗi loại → hiện LineChart
  │
  ├─ deviceApi.getAllDevices()   → GET /api/devices
  │   → lấy lastAction của từng LED → đồng bộ trạng thái switch
  │
  └─ webSocketService.connect()  → ws://localhost:8080/ws/sensors (SockJS)
      ├─ subscribe /topic/sensors       → onSensorData → update StatCard + Chart realtime
      └─ subscribe /topic/devices/1,2,3/status → onDeviceStatus → update switch + History
```

---

## 6. WebSocket Service — Cơ chế kết nối

```
websocketService (singleton, khởi tạo khi app load)
  │
  ├─ Client(@stomp/stompjs) với webSocketFactory = new SockJS(...)
  ├─ reconnectDelay = 5000ms (tự reconnect nếu mất kết nối)
  │
  ├─ onConnect:
  │   ├─ subscribe /topic/sensors → gọi tất cả sensorListeners
  │   └─ subscribe /topic/devices/{id}/status → gọi deviceListeners[id]
  │
  ├─ onSensorData(callback) → thêm vào sensorListeners Set
  └─ onDeviceStatus(id, callback) → thêm vào deviceListeners Map[id]
```

---

## 7. Bảng endpoint REST API

| Method | Endpoint | Mô tả |
|--------|----------|-------|
| GET | `/api/datasensors/latest` | Dữ liệu cảm biến mới nhất |
| GET | `/api/datasensors/chart/latest` | 10 điểm gần nhất cho biểu đồ |
| GET | `/api/datasensors` | Tìm kiếm có phân trang |
| GET | `/api/devices` | Danh sách thiết bị + trạng thái |
| POST | `/api/devices/{id}/actions` | Gửi lệnh ON/OFF |
| GET | `/api/actions` | Lịch sử hành động (có filter) |

## 8. Bảng MQTT topic

| Topic | Chiều | Nội dung |
|-------|-------|----------|
| `iot/sensors/data` | ESP → Backend | `{temperature, humidity, light, timestamp}` |
| `iot/devices/{id}/action` | Backend → ESP | `{actionId, deviceId, action}` |
| `iot/devices/{id}/status` | ESP → Backend | `{actionId, deviceId, status}` |

## 9. Bảng WebSocket topic (STOMP)

| Topic | Chiều | Nội dung |
|-------|-------|----------|
| `/topic/sensors` | Backend → Frontend | `LatestDataSensorResponse` |
| `/topic/devices/{id}/status` | Backend → Frontend | `ActionResponse` (SUCCESS/FAILED) |
