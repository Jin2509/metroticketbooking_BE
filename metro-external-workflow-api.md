# Metro, External và Workflow API

Tài liệu này mô tả các module `metro`, `external`, `workflow` và cách xem API bằng Swagger.
Các route bên dưới được tổng hợp từ controller hiện có trong source.

## 1. Swagger / OpenAPI

Khi backend chạy tại `http://localhost:8080`:

- Swagger UI: <http://localhost:8080/swagger-ui/index.html>
- OpenAPI JSON: <http://localhost:8080/v3/api-docs>
- OpenAPI YAML: <http://localhost:8080/v3/api-docs.yaml>

Swagger UI và OpenAPI được mở công khai để tra cứu. Các API `/api/**` yêu cầu access token:

```http
Authorization: Bearer <access-token>
```

Trong Swagger UI, bấm **Authorize** và nhập access token theo cấu hình Bearer JWT. Request/response schema chi tiết được hiển thị trực tiếp trong Swagger.

## 2. Quy ước chung

- Base path của các controller trong tài liệu này: `/api/v1`.
- Kiểu dữ liệu request/response: JSON.
- `Long` là ID số; UUID gửi dưới dạng chuỗi UUID.
- API danh sách có phân trang nhận các tham số Spring Data như `page`, `size`, `sort`; mặc định `size=20` ở endpoint phân trang.
- Endpoint xóa trả `204 No Content` nếu thành công.
- ID trong `{...}` là path parameter, ví dụ `/stations/{stationId}`.

## 3. Cấu trúc module

### `metro`

Quản lý các thực thể cốt lõi:

- `line`: tuyến Metro, tìm kiếm và quan hệ tuyến–ga.
- `station`: ga, tọa độ, tuyến đi qua ga và kết nối chuyển tuyến.
- `train`: đoàn tàu và trạng thái hoạt động.
- `operation`: cấu hình vận hành theo phiên bản, có workflow phê duyệt và kích hoạt.
- `announcement`: thông báo gắn với tuyến/ga, có workflow phê duyệt.

Các response API dùng DTO, ví dụ `LineResponse`, `StationResponse`, `LineStationResponse`, `TrainResponse`, `OperationConfigResponse` và `AnnouncementResponse`.

### `external`

Tích hợp dữ liệu nhà cung cấp ngoài:

- `bus`: tuyến xe buýt và điểm dừng gần tọa độ/ga; service có thể dùng dữ liệu mẫu dự phòng tùy cấu hình nhà cung cấp.
- `facility`: tiện ích xung quanh ga lấy từ Geoapify Places API. Cần cấu hình `GEOAPIFY_API_KEY`; nếu provider không truy cập được, endpoint facilities trả `502 Bad Gateway`.

Facilities hỗ trợ các category: `FOOD`, `HEALTH`, `EDUCATION`, `SHOP`, `PARK`, `BANK`, `TRANSPORT`, `ENTERTAINMENT`, `OTHER`.

### `workflow`

`workflow` là service/domain nội bộ, **không có REST controller hoặc endpoint độc lập**. Nó kiểm tra chuyển trạng thái và vai trò được phép. Client thao tác qua API của announcement hoặc operation config ở mục 4. Các trạng thái workflow không phải API riêng.

## 4. Danh sách API

### 4.1. Tuyến Metro — `MetroLineController`

| Method | Endpoint | Mô tả / tham số |
|---|---|---|
| GET | `/api/v1/lines` | Danh sách tuyến phân trang. Query: `code`, `name`, `status`, `page`, `size`, `sort`. |
| GET | `/api/v1/lines/all` | Tất cả tuyến, không phân trang. |
| GET | `/api/v1/lines/{lineId}` | Chi tiết tuyến. |
| POST | `/api/v1/lines` | Tạo tuyến. Body: `CreateLineRequest`; trả `201`. |
| PUT | `/api/v1/lines/{lineId}` | Cập nhật tuyến. Body: `UpdateLineRequest`. |
| DELETE | `/api/v1/lines/{lineId}` | Xóa tuyến; trả `204`. |
| GET | `/api/v1/lines/{lineId}/stations` | Các ga thuộc tuyến, theo thứ tự. |
| GET | `/api/v1/public/lines/{lineCode}/stations` | Danh sách ga công khai theo mã tuyến, theo thứ tự; dùng để khởi tạo bản đồ tuyến. Không yêu cầu access token. |
| POST | `/api/v1/lines/{lineId}/stations` | Thêm ga vào tuyến. Body: `AddLineStationRequest`; trả `201`. |
| PUT | `/api/v1/lines/{lineId}/stations/{stationId}/segment` | Cập nhật thông số đoạn tuyến. Body: `UpdateLineStationSegmentRequest`. |
| DELETE | `/api/v1/lines/{lineId}/stations/{stationId}` | Gỡ ga khỏi tuyến, không xóa bản ghi ga; trả `204`. |
| PUT | `/api/v1/lines/{lineId}/stations/order` | Sắp xếp toàn bộ ga. Body là danh sách `StationOrderItem`; phải gửi đầy đủ ga của tuyến. |
| GET | `/api/v1/lines/{lineId}/transfers` | Các kết nối chuyển tuyến liên quan đến tuyến. |

`CreateLineRequest` / `UpdateLineRequest`: `code`, `nameLine`, `colorHex`, `description`, `status`.

`AddLineStationRequest`: `stationId`, `stationOrder`, `distanceFromPrevKm`, `travelTimeFromPrevSeconds`, `dwellTimeSeconds`.

### 4.2. Tích hợp bản đồ và vị trí tàu realtime

Frontend nên tải dữ liệu tĩnh của tuyến và ga một lần bằng REST, sau đó dùng WebSocket chỉ để cập nhật vị trí tàu:

1. Gọi `GET /api/v1/public/lines/{lineCode}/stations` để nhận danh sách ga theo thứ tự, gồm tọa độ ga và thông tin đoạn tuyến.
2. Dùng dữ liệu đó để hiển thị marker ga và vẽ tuyến trên bản đồ. API hiện chưa cung cấp polyline/GeoJSON của tim đường ray; nối các tọa độ ga chỉ tạo ra đường biểu diễn xấp xỉ.
3. Kết nối WebSocket tới `/ws/lines/{lineCode}/train-positions`. Server gửi snapshot ngay khi kết nối thành công, sau đó phát snapshot mới mỗi giây. Frontend cập nhật marker tàu từ các snapshot này; không cần tải lại ga qua WebSocket.

Ví dụ thông điệp vị trí:

```json
{
  "type": "positions",
  "lineId": "BTS-01",
  "updatedAt": "2026-11-06T14:00:00Z",
  "trains": [
    {
      "trainId": "TR-001",
      "lineId": "BTS-01",
      "lat": 10.77096,
      "lng": 106.69776,
      "nextStationId": "ST002",
      "etaSec": 42,
      "status": "RUNNING",
      "headsignStationId": "ST014"
    }
  ]
}
```

WebSocket dùng cùng danh sách origin được cấu hình bởi `APP_CORS_ALLOWED_ORIGINS` (mặc định `http://localhost:3000,http://localhost:5173`). Origin của frontend cần được thêm vào cấu hình khi triển khai. Endpoint WebSocket không yêu cầu access token theo `SecurityConfig`; không gửi dữ liệu riêng tư qua kết nối này.

Vị trí được mô phỏng từ cấu hình `ACTIVE` đang hiệu lực, giờ vận hành, danh sách tàu sẵn sàng và dữ liệu thời gian từng đoạn tuyến. Ngoài giờ vận hành, snapshot có thể có `trains: []`. Nếu không có cấu hình ACTIVE hiệu lực hoặc dữ liệu không đủ để mô phỏng, WebSocket gửi thông điệp `type: "error"` rồi đóng kết nối.

### 4.3. Ga Metro — `StationController`

| Method | Endpoint | Mô tả / tham số |
|---|---|---|
| GET | `/api/v1/stations` | Danh sách ga phân trang. Query: `q`, `lineId`, `status`, `page`, `size`, `sort`. |
| GET | `/api/v1/stations/all` | Tất cả ga, không phân trang. |
| GET | `/api/v1/stations/nearest` | Ga gần tọa độ. Query bắt buộc: `lat`, `lng`; `radiusKm` mặc định `5`. |
| GET | `/api/v1/stations/{stationId}` | Chi tiết ga. |
| POST | `/api/v1/stations` | Tạo ga. Body: `CreateStationRequest`; trả `201`. |
| PUT | `/api/v1/stations/{stationId}` | Cập nhật ga. Body: `UpdateStationRequest`. |
| DELETE | `/api/v1/stations/{stationId}` | Xóa ga; trả `204`. |
| GET | `/api/v1/stations/{stationId}/connections` | Các kết nối chuyển tuyến tại ga. |
| GET | `/api/v1/stations/{stationId}/lines` | Các tuyến đi qua ga. |

`CreateStationRequest` / `UpdateStationRequest`: `code`, `nameStation`, `latitude`, `longitude`, `address`, `district`, `imageUrl`, `isInterchange`, `status`.

### 4.4. Kết nối chuyển tuyến — `LineTransferController`

| Method | Endpoint | Mô tả / tham số |
|---|---|---|
| GET | `/api/v1/line-transfers` | Danh sách kết nối chuyển tuyến. |
| GET | `/api/v1/line-transfers/{id}` | Chi tiết kết nối. |
| POST | `/api/v1/line-transfers` | Tạo kết nối. Body: `CreateLineTransferRequest`; trả `201`. |
| PUT | `/api/v1/line-transfers/{id}` | Cập nhật kết nối. Body: `UpdateLineTransferRequest`. |
| DELETE | `/api/v1/line-transfers/{id}` | Xóa kết nối; trả `204`. |

Request có các trường `stationId`, `fromLineId`, `toLineId`, `transferTimeMinutes`, `status`.

### 4.5. Tàu — `TrainController`

| Method | Endpoint | Mô tả / tham số |
|---|---|---|
| GET | `/api/v1/trains` | Danh sách tàu phân trang (`page`, `size`, `sort`). |
| GET | `/api/v1/trains/all` | Tất cả tàu. |
| GET | `/api/v1/trains/{trainId}` | Chi tiết tàu. |
| GET | `/api/v1/lines/{lineId}/trains` | Tàu thuộc một tuyến. |
| POST | `/api/v1/trains` | Tạo tàu. Body: `CreateTrainRequest`; trả `201`. |
| PUT | `/api/v1/trains/{trainId}` | Cập nhật tàu. Body: `UpdateTrainRequest`. |
| PATCH | `/api/v1/trains/{trainId}/status` | Đổi trạng thái; body là giá trị `TrainStatus`. |
| DELETE | `/api/v1/trains/{trainId}` | Xóa tàu; trả `204`. |

`CreateTrainRequest` / `UpdateTrainRequest`: `code`, `nameTrain`, `lineId`, `capacity`, `manufactureYear`, `status`.

### 4.6. Cấu hình vận hành — `OperationConfigController`

| Method | Endpoint | Mô tả / tham số |
|---|---|---|
| GET | `/api/v1/operation-configs` | Tất cả cấu hình vận hành. |
| GET | `/api/v1/lines/{lineId}/operation-configs` | Lịch sử cấu hình theo tuyến. |
| GET | `/api/v1/operation-configs/{id}` | Chi tiết phiên bản cấu hình. |
| POST | `/api/v1/lines/{lineId}/operation-configs` | Tạo cấu hình nháp; body `CreateOperationConfigRequest`; trả `201`. |
| PUT | `/api/v1/operation-configs/{id}` | Cập nhật draft hoặc tạo draft phiên bản mới từ approved; body `UpdateOperationConfigRequest`. |
| POST | `/api/v1/operation-configs/{id}/submit` | Gửi duyệt: `DRAFT -> PENDING_APPROVAL`. |
| POST | `/api/v1/operation-configs/{id}/approve` | Phê duyệt: `PENDING_APPROVAL -> APPROVED`. Query bắt buộc: `approvedBy` (UUID). |
| POST | `/api/v1/operation-configs/{id}/reject` | Từ chối: `PENDING_APPROVAL -> REJECTED`. Query bắt buộc: `reason`. |
| POST | `/api/v1/operation-configs/{id}/activate` | Kích hoạt cấu hình đã duyệt. |
| POST | `/api/v1/operation-configs/{id}/deactivate` | Đưa cấu hình `ACTIVE` hoặc `APPROVED` về `INACTIVE`. |

`CreateOperationConfigRequest`: `lineId`, `numberOfTrains`, `headwayMinutes`, `runningTimeMinutes`, `turnaroundTimeMinutes`, `startTime`, `endTime`, `effectiveFrom`, `effectiveTo`, `createdBy`.

`UpdateOperationConfigRequest`: giống request tạo, ngoại trừ `lineId`. Không gửi `version` hoặc `status`; backend quản lý các trường này.

### 4.7. Thông báo Metro — `AnnouncementController`

| Method | Endpoint | Mô tả / tham số |
|---|---|---|
| GET | `/api/v1/announcements` | Danh sách thông báo. Query tùy chọn: `status`. |
| GET | `/api/v1/lines/{lineId}/announcements` | Thông báo theo tuyến. |
| GET | `/api/v1/stations/{stationId}/announcements` | Thông báo theo ga. |
| GET | `/api/v1/announcements/{id}` | Chi tiết thông báo. |
| POST | `/api/v1/announcements` | Tạo thông báo nháp. Body: `CreateAnnouncementRequest`; trả `201`. |
| PUT | `/api/v1/announcements/{id}` | Cập nhật thông báo nháp. Body: `UpdateAnnouncementRequest`. |
| POST | `/api/v1/announcements/{id}/submit` | Gửi duyệt: `DRAFT -> PENDING_APPROVAL`. |
| POST | `/api/v1/announcements/{id}/approve` | Phê duyệt. Query bắt buộc: `approvedBy` (UUID). |
| POST | `/api/v1/announcements/{id}/reject` | Từ chối. Query bắt buộc: `reason`. |

Request tạo/cập nhật gồm `lineId`, `stationId`, `title`, `content`, `severity`, `startsAt`, `endsAt`, `scope`, `targetRole`, `createdBy`. Trạng thái được điều khiển qua các endpoint workflow, không cập nhật trực tiếp từ body.

### 4.8. Dữ liệu xe buýt — `BusController`

| Method | Endpoint | Mô tả / tham số |
|---|---|---|
| GET | `/api/v1/bus/routes` | Danh sách tuyến xe buýt. |
| GET | `/api/v1/bus/stops/nearby` | Điểm dừng gần tọa độ. Query bắt buộc: `lat`, `lng`; `radiusKm` mặc định `1.0`, `limit` mặc định `10`. |
| GET | `/api/v1/stations/{stationId}/bus` | Điểm dừng xe buýt gần ga. Query: `radiusKm` mặc định `1.0`, `limit` mặc định `10`. |

Cache bus đang được giữ trong bộ nhớ của từng instance backend: `bus-nearby` có TTL 15 phút và `bus-routes` có TTL 30 phút. Cache key phụ thuộc vào ga hoặc tọa độ cùng với các tham số bán kính/giới hạn; request cùng key trong thời hạn cache sẽ không gọi lại provider. Cache bị mất khi instance khởi động lại và không chia sẻ giữa nhiều instance.

### 4.9. Tiện ích quanh ga — `FacilityController`

| Method | Endpoint | Mô tả / tham số |
|---|---|---|
| GET | `/api/v1/stations/{stationId}/facilities` | Tối đa 10 tiện ích Geoapify trong bán kính của ga. Query: `category` tùy chọn; `radiusMeters` mặc định `500`. Khi chọn category, backend gửi category đó trong query Geoapify trước khi giới hạn kết quả. |

`category` nhận một trong `FOOD`, `HEALTH`, `EDUCATION`, `SHOP`, `PARK`, `BANK`, `TRANSPORT`, `ENTERTAINMENT`, `OTHER`. Response là danh sách `FacilityDto`: `id`, `name`, `category`, `latitude`, `longitude`, `distanceMeters`, `address`, `source`.

Kết quả facilities được cache trong bộ nhớ của từng instance trong 60 phút theo ga, category và bán kính. Cache bị mất khi instance khởi động lại và không chia sẻ giữa nhiều instance. Khi truyền `category`, Geoapify được gọi với `limit=10`. Khi không truyền category, backend gọi Geoapify riêng cho từng category, lấy tối đa 2 kết quả/category, rồi ghép luân phiên và trả tối đa 10 facility. Vì dữ liệu mỗi category được hỏi riêng, kết quả không bị một category phổ biến lấn hết giới hạn của truy vấn chung.

## 5. Workflow và vai trò

`WorkflowEngineService` định nghĩa hai workflow:

| Đối tượng | Luồng trạng thái | Vai trò theo cấu hình workflow |
|---|---|---|
| Announcement | `DRAFT -> PENDING_APPROVAL -> APPROVED` hoặc `REJECTED` | `STAFF`, `ADMIN`, `SYSTEM` gửi duyệt; `ADMIN`, `SYSTEM` phê duyệt/từ chối. |
| Operation config | `DRAFT -> PENDING_APPROVAL -> APPROVED` hoặc `REJECTED`; sau đó `ACTIVE` / `INACTIVE` | `STAFF`, `ADMIN`, `SYSTEM` gửi duyệt; `ADMIN`, `SYSTEM` duyệt, từ chối, kích hoạt hoặc vô hiệu hóa. |

`ADMIN` và `SYSTEM` được workflow engine cho phép vượt qua kiểm tra vai trò transition. Quyền truy cập HTTP tổng thể vẫn chịu `SecurityConfig` và các rule RBAC của API.

## 6. Ghi chú triển khai

- `metro` và `external` đều dùng prefix `/api/v1`; không nhầm với một số API cũ trong `api.md` đang dùng `/api` hoặc chưa mô tả module Metro.
- Endpoint facilities gọi Geoapify Places API bên ngoài; cấu hình khóa bằng `GEOAPIFY_API_KEY` (không commit khóa vào repository). `502` nghĩa là provider không truy cập được hoặc trả response không hợp lệ, không đồng nghĩa chắc chắn rằng khu vực không có tiện ích. Nếu chưa cấu hình khóa, endpoint trả `503 Service Unavailable`.
- TTL cache hiện được đặt cố định trong `CacheConfig`: bus gần vị trí/ga 15 phút, danh sách tuyến bus 30 phút và facilities 60 phút. Các biến `APP_EXTERNAL_CACHE_BUS_NEARBY_TTL_MINUTES`, `APP_EXTERNAL_CACHE_BUS_ROUTES_TTL_MINUTES`, `APP_EXTERNAL_CACHE_FACILITY_NEARBY_TTL_MINUTES` hiện có trong `application.properties` nhưng chưa được `CacheConfig` đọc; thay đổi các biến này hiện chưa thay đổi TTL thực tế.
- Cache hiện là in-memory theo từng tiến trình backend, không phải Redis/distributed cache. Nhiều instance có cache riêng; dữ liệu hết khi instance khởi động lại.
- Không có endpoint kiểu `/api/v1/workflows`; hãy dùng các route `submit`, `approve`, `reject`, `activate`, `deactivate` tương ứng ở trên.
