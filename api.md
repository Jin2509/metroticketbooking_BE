# MetroTicket API

Tài liệu dành cho đội frontend **web** và **mobile**. Cả hai nền tảng dùng chung một backend và cùng một bộ API; điểm khác nhau được ghi trong [mục 3.4](#34-khác-nhau-giữa-web-và-mobile).

Hiện có: xác thực, hồ sơ cá nhân, lịch sử bảo mật, thông báo, quản lý người dùng và phân quyền. API vé, ga, tuyến chưa có.

Mới bắt đầu thì đọc [mục 1.1](#11-tài-khoản-để-test) để đăng nhập thử và [mục 11](#11-thứ-tự-thực-hiện-ở-frontend) để biết nên làm gì trước.

## 1. Quy ước chung

| Mục | Quy ước |
|---|---|
| Base URL (local) | `http://localhost:8080`. Máy ảo Android dùng `http://10.0.2.2:8080`, máy thật dùng IP LAN của máy chạy backend |
| Định dạng | JSON UTF-8. Có body thì gửi `Content-Type: application/json` |
| Xác thực | Header `Authorization: Bearer <accessToken>`. Backend không dùng cookie hay session |
| Thời gian | ISO-8601 theo UTC, ví dụ `2026-11-15T11:32:36.696621Z`. Số chữ số lẻ của giây không cố định, nên dùng thư viện để parse |
| ID người dùng | UUID dạng chuỗi |
| Email | Không phân biệt hoa thường. Backend lưu và trả về dạng chữ thường |
| Mã OTP | Chuỗi đúng 6 chữ số, giữ cả số 0 ở đầu. Không lưu dạng số |
| Mật khẩu mới | Từ 8 ký tự, tối đa **72 byte** UTF-8 (72 ký tự có dấu sẽ vượt giới hạn), không được toàn khoảng trắng |
| Phiên bản API | Chưa có tiền tố version |

Client phải bỏ qua các field lạ trong response, vì backend có thể thêm field mới. Bản app mobile cũ vẫn chạy song song với bản mới, nên backend phải báo trước khi đổi hoặc bỏ một field đang dùng.

### 1.1. Tài khoản để test

Tài khoản test nằm trong file `seed/demo-users.json` của backend, ghi rõ email và mật khẩu dạng chữ thường; backend tự băm khi nạp vào DB. File này không có trong repo, nên hãy xin mật khẩu của DB dùng chung từ trưởng nhóm backend.

Chạy với DB riêng: sao chép `seed/demo-users.example.json` thành `seed/demo-users.json`, sửa mật khẩu, đặt `SEED_DEMO_DATA=true` trong `.env` rồi khởi động lại. Sửa file rồi khởi động lại là DB cập nhật theo. Tính năng này luôn tắt với profile `prod`.

**Web quản trị chỉ cho vai trò `ADMIN` vào**, nên hãy dùng `admin.demo`. Backend hiện chưa có vai trò `MANAGER` và `ACCOUNTANT` mà web khai báo. Tài khoản `staff` và `passenger` dùng để test app mobile; đăng nhập chúng trên web sẽ nhận thông báo "không có quyền truy cập trang quản trị".

| Email | Vai trò | Kết quả login |
|---|---|---|
| `admin.demo@metroticket.test` | `ADMIN` | 200, có mọi quyền |
| `staff.demo@metroticket.test` | `STAFF` | 200 |
| `passenger.demo@metroticket.test` | `PASSENGER` | 200 |
| `pending.demo@metroticket.test` | `PASSENGER` | 403 `Email chưa được xác thực` |
| `locked.demo@metroticket.test` | `PASSENGER` | 403 `Tài khoản đã bị khoá` |

Tài khoản `ACTIVE` mới tạo có sẵn 3 thông báo mẫu, trong đó 1 đã đọc.

Các địa chỉ `.test` không nhận được mail. Để thử OTP (đăng ký, quên mật khẩu, đổi email), hãy đăng ký bằng email thật hoặc chạy backend với Mailpit (`docker compose --profile mailpit up -d`, xem mail ở `http://localhost:8025`).

```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"passenger.demo@metroticket.test","password":"Demo@12345"}'
```

## 2. Xử lý lỗi

Lỗi do API trả về có `Content-Type: application/problem+json`:

```json
{
  "detail": "Email hoặc mật khẩu không đúng",
  "instance": "/api/auth/login",
  "status": 401,
  "title": "Unauthorized"
}
```

Lỗi dữ liệu đầu vào có thêm `errors`, với key là tên field:

```json
{
  "detail": "Dữ liệu không hợp lệ",
  "instance": "/api/auth/register",
  "status": 400,
  "title": "Bad Request",
  "errors": {
    "email": "must be a well-formed email address",
    "password": "Mật khẩu từ 8 ký tự, tối đa 72 byte"
  }
}
```

Khi xử lý lỗi cần lưu ý:

- **Có thể không có body.** Thiếu token, token hỏng (401) và thiếu quyền (403) do lớp bảo mật trả về với body rỗng. Luôn kiểm tra trước khi parse JSON.
- **Chưa có mã lỗi riêng.** Hãy rẽ nhánh theo HTTP status và endpoint. Trường hợp duy nhất buộc phải so sánh `detail` là login trả 403 ([mục 5](#post-apiauthlogin)).
- **Nội dung `errors` có thể là tiếng Anh.** Hãy tự kiểm tra và tự viết thông báo ở client, chỉ dùng `errors` để biết field nào sai.

| Status | Ý nghĩa | Client nên làm |
|---|---|---|
| 400 | Dữ liệu sai, mã OTP sai hoặc hết hạn, mật khẩu hiện tại sai | Hiển thị lỗi, cho nhập lại |
| 401 | Token thiếu, hết hạn, bị thu hồi; hoặc sai thông tin đăng nhập | Với API cần đăng nhập: làm theo [mục 3.3](#33-xử-lý-401-và-làm-mới-token) |
| 403 | Không đủ quyền; hoặc tài khoản chưa xác thực/bị khoá khi login | Hiển thị thông báo, **không** gọi refresh |
| 404 | Không tìm thấy | Hiển thị thông báo |
| 405 | Sai HTTP method | Lỗi lập trình ở client |
| 409 | Dữ liệu trùng, hoặc request song song bị xung đột | Hiển thị lỗi, hoặc thử lại nếu là xung đột |
| 429 | Vượt giới hạn tần suất | Xem [mục 4](#4-giới-hạn-và-thời-hạn) |
| 5xx | Lỗi server | Thông báo chung, cho thử lại |

## 3. Token và phiên đăng nhập

### 3.1. Hai loại token

| | Access token | Refresh token |
|---|---|---|
| Dùng để | Gọi API | Lấy cặp token mới |
| Thời hạn | 15 phút | 14 ngày, tính lại sau mỗi lần refresh |
| Gửi qua | Header `Authorization` | Body của `/refresh` và `/logout` |

Mỗi lần login tạo một phiên riêng, nên một tài khoản đăng nhập được cùng lúc trên nhiều thiết bị. Không đọc vai trò hay email từ nội dung JWT, vì chúng có thể đã cũ. Hãy lấy từ `GET /api/me`.

### 3.2. Refresh token chỉ dùng được một lần

Mỗi lần refresh, backend trả về refresh token **mới** và thu hồi token cũ. Client phải lưu token mới ngay.

Nếu một token đã dùng bị gửi lại, backend coi như token bị lộ và **thu hồi cả phiên**, người dùng phải đăng nhập lại. Vì vậy:

- Tại một thời điểm chỉ được có **một** request refresh. Các request khác bị 401 phải chờ kết quả của request đó.
- Web: nhiều tab dùng chung token cũng phải phối hợp, ví dụ bằng Web Locks API.
- Mobile: tác vụ nền và luồng chính phải dùng chung một cơ chế refresh.

Backend không có thời gian ân hạn. Nếu request refresh đã tới server nhưng mạng mất trước khi client nhận được response, thì token đang lưu đã bị thu hồi và lần thử lại sẽ kết thúc phiên. Khi đó đưa người dùng về màn đăng nhập.

### 3.3. Xử lý 401 và làm mới token

```text
Gọi API có token → nhận 401
  ├─ Đã có request refresh đang chạy → chờ nó xong
  └─ Chưa có → POST /api/auth/refresh
       ├─ 200 → lưu cả 2 token mới, gọi lại request ban đầu (chỉ 1 lần)
       ├─ 401/403 → xoá token, về màn đăng nhập
       └─ Lỗi mạng/5xx → giữ token, báo lỗi kết nối, chưa đăng xuất
```

Có thể refresh trước khi hết hạn theo `expiresAt`, nhưng vẫn phải xử lý 401: đồng hồ thiết bị có thể sai, và token có thể bị thu hồi trước hạn.

Các sự kiện sau làm token hiện tại mất hiệu lực trước hạn:

| Sự kiện | Access token | Refresh token | Client cần làm |
|---|---|---|---|
| Logout | Thu hồi token gửi kèm | Thu hồi phiên đó | Xoá token. Thiết bị khác không bị ảnh hưởng |
| Đổi mật khẩu, quên mật khẩu, admin đặt lại mật khẩu | Thu hồi trên **mọi thiết bị** | Thu hồi trên **mọi thiết bị** | Đăng nhập lại |
| Đổi email thành công | Thu hồi trên mọi thiết bị | Thu hồi trên mọi thiết bị | Đăng nhập lại bằng email mới |
| Admin khoá tài khoản | Thu hồi trên mọi thiết bị | Thu hồi trên mọi thiết bị | Không đăng nhập được cho tới khi mở khoá |
| Admin đổi vai trò | Thu hồi trên mọi thiết bị | **Vẫn dùng được** | Tự xử lý qua refresh, nên gọi lại `GET /api/me` |

Khi admin đổi **quyền của một vai trò**, thay đổi có hiệu lực ngay mà token không bị thu hồi. Vì vậy client có thể nhận 403 ở API trước đó vẫn gọi được. Khi đó nên tải lại `GET /api/me`.

### 3.4. Khác nhau giữa web và mobile

API và token giống hệt nhau. Điểm khác nằm ở cách client dùng:

| | Web | Mobile |
|---|---|---|
| CORS | Có áp dụng. Origin phải được cấu hình ở backend (`APP_CORS_ALLOWED_ORIGINS`, mặc định `http://localhost:3000` và `http://localhost:5173`). Khi có domain mới thì báo backend thêm | Không áp dụng |
| Header được phép | Chỉ `Authorization` và `Content-Type`. Thêm header khác sẽ bị trình duyệt chặn | Không giới hạn |
| Đọc `Retry-After` | Đọc được | Đọc được |
| Cookie | Không dùng. Không bật `credentials: 'include'` / `withCredentials`, vì CORS sẽ chặn | Không dùng |
| Lưu refresh token | Backend trả token trong JSON nên client tự lưu. Tránh `localStorage` nếu được, vì script độc hại trên trang (XSS) đọc được | Keychain (iOS), Keystore/EncryptedSharedPreferences (Android) |

## 4. Giới hạn và thời hạn

| Giới hạn | Giá trị | Khi vượt |
|---|---|---|
| Hiệu lực mã OTP | 10 phút | 400, phải yêu cầu mã mới |
| Chờ giữa hai lần gửi mã | 60 giây | 429 có header `Retry-After` (giây). Nên hiện đếm ngược |
| Nhập sai OTP | 5 lần cho mỗi mã | 429, phải yêu cầu mã mới |
| Sai mật khẩu | 10 lần liên tiếp theo email | 429 trong 15 phút, **không có** `Retry-After`. Nhập đúng cũng bị từ chối |
| Số bản ghi mỗi trang | Tối đa 100 | Tự giảm về 100 |

Số lần sai mật khẩu tính chung cho login và các API yêu cầu `currentPassword`. Yêu cầu mã mới sẽ làm mã cũ mất hiệu lực, nên chỉ mã trong mail mới nhất dùng được.

## 5. Xác thực: `/api/auth`

Các API này không cần token, và chỉ nhận `POST`.

| Endpoint | Chức năng | Thành công |
|---|---|---|
| `POST /api/auth/register` | Đăng ký, gửi OTP | 202 |
| `POST /api/auth/verify-email` | Xác thực email bằng OTP | 204 |
| `POST /api/auth/resend-code` | Gửi lại OTP đăng ký | 202 |
| `POST /api/auth/login` | Đăng nhập | 200, hoặc 202 nếu cần xác nhận 2 lớp |
| `POST /api/auth/login/2fa` | Bước 2 của đăng nhập: nhập mã gửi qua email | 200 |
| `POST /api/auth/login/2fa/resend` | Gửi lại mã đăng nhập | 202 |
| `POST /api/auth/refresh` | Lấy cặp token mới | 200 |
| `POST /api/auth/logout` | Đăng xuất | 204 |
| `POST /api/auth/forgot-password` | Gửi OTP đặt lại mật khẩu | 202 |
| `POST /api/auth/reset-password` | Đặt lại mật khẩu bằng OTP | 204 |

**Luồng đăng ký:** `register` → màn nhập OTP → `verify-email` → `login`. Xác thực xong backend không tự đăng nhập.

**Luồng đăng nhập có xác nhận 2 lớp:** `login` trả 202 kèm `ticket` → màn nhập OTP → `login/2fa` trả token. Tài khoản chưa bật thì `login` trả 200 như cũ.

**Luồng quên mật khẩu:** `forgot-password` → màn nhập OTP và mật khẩu mới → `reset-password` → `login`.

Mail được gửi bất đồng bộ. Response 202 chỉ có nghĩa là yêu cầu đã được nhận, nên màn nhập OTP cần có nút gửi lại.

### `POST /api/auth/register`

```json
{ "email": "an@example.com", "password": "MatKhau@123", "fullName": "Nguyễn An" }
```

`fullName` tối đa 120 ký tự, `email` tối đa 320 ký tự. Thành công: 202, không có body.

| Lỗi | Khi nào | Client nên làm |
|---|---|---|
| 400 | Dữ liệu sai | Hiển thị theo field |
| 409 | Email đã có tài khoản đã xác thực hoặc bị khoá | Gợi ý đăng nhập hoặc quên mật khẩu |
| 429 | Email đang chờ xác thực và mã còn hiệu lực. `Retry-After` là số giây tới khi mã hết hạn (tối đa 10 phút) | Chuyển sang màn nhập OTP. Mật khẩu vừa nhập **không** được lưu |

Nếu email chưa xác thực và mã đã hết hạn, đăng ký lại sẽ ghi đè mật khẩu và họ tên, rồi gửi mã mới.

### `POST /api/auth/verify-email`

```json
{ "email": "an@example.com", "code": "012345" }
```

Thành công: 204.

| Lỗi | Khi nào |
|---|---|
| 400 | Mã sai, hết hạn hoặc không tồn tại |
| 409 | Request gửi trùng cùng lúc (chặn nút bấm khi đang gửi) |
| 429 | Sai quá 5 lần, phải yêu cầu mã mới |

### `POST /api/auth/resend-code`

```json
{ "email": "an@example.com" }
```

Trả 202 kể cả khi email không tồn tại hoặc đã xác thực (khi đó không gửi mail). Lỗi: 400, hoặc 429 có `Retry-After`. Mật khẩu đã đăng ký được giữ nguyên.

### `POST /api/auth/login`

```json
{ "email": "an@example.com", "password": "MatKhau@123" }
```

Thành công: 200.

```json
{
  "accessToken": "<jwt>",
  "tokenType": "Bearer",
  "expiresAt": "2026-11-01T11:47:36.697837Z",
  "refreshToken": "<chuỗi ngẫu nhiên>",
  "refreshTokenExpiresAt": "2026-11-15T11:32:36.696621Z"
}
```

Tài khoản đã bật xác nhận 2 lớp: mật khẩu đúng trả **202, chưa có token**, và mã được gửi qua email. Web và mobile đều phải kiểm tra status này, không được đọc token khi nhận 202.

```json
{
  "twoFactorRequired": true,
  "ticket": "<chuỗi ngẫu nhiên>",
  "expiresAt": "2026-11-01T11:42:36.697837Z"
}
```

`expiresAt` là hạn của mã. Chỉ giữ `ticket` trong bộ nhớ, không ghi xuống thiết bị. Đăng nhập lại trong 60 giây sau khi gửi mã sẽ nhận ticket mới (ticket cũ mất hiệu lực) nhưng không có mail mới, mã trong mail trước vẫn dùng được.

| Lỗi | `detail` | Client nên làm |
|---|---|---|
| 400 | Dữ liệu sai | Hiển thị theo field |
| 401 | Email hoặc mật khẩu không đúng | Hiển thị đúng thông báo này, không phân biệt email hay mật khẩu sai |
| 403 | `Email chưa được xác thực` | Chuyển sang màn OTP, gọi `resend-code` nếu cần |
| 403 | `Tài khoản đã bị khoá` | Báo liên hệ quản trị viên |
| 429 | Nhập sai quá nhiều lần | Báo thử lại sau 15 phút |

Hai trường hợp 403 hiện chỉ phân biệt được bằng chuỗi `detail`.

### `POST /api/auth/login/2fa`

```json
{ "ticket": "<ticket>", "code": "012345" }
```

Thành công: 200, cùng định dạng token với login.

| Lỗi | `detail` | Client nên làm |
|---|---|---|
| 400 | Mã sai hoặc hết hạn | Cho nhập lại hoặc gửi lại mã |
| 400 | `Phiên xác nhận đã hết hạn, vui lòng đăng nhập lại` | Về màn đăng nhập. Xảy ra khi ticket sai, mã đã dùng, hoặc mật khẩu/email vừa đổi |
| 403 | `Tài khoản đã bị khoá` | Báo liên hệ quản trị viên |
| 409 | Request gửi trùng | Chặn nút bấm khi đang gửi |
| 429 | Sai 5 lần cho mỗi mã, hoặc tổng số lần sai đạt giới hạn đăng nhập | Gửi lại mã, hoặc thử lại sau 15 phút |

Nhập sai mã được tính chung vào bộ đếm sai mật khẩu ([mục 4](#4-giới-hạn-và-thời-hạn)), và đúng mật khẩu ở bước 1 không xoá bộ đếm này.

### `POST /api/auth/login/2fa/resend`

```json
{ "ticket": "<ticket>" }
```

Thành công: 202, ticket giữ nguyên và mã cũ mất hiệu lực. Lỗi: 400 (phiên hết hạn, về màn đăng nhập), 429 có `Retry-After`.

### `POST /api/auth/refresh`

```json
{ "refreshToken": "<refresh token>" }
```

Thành công: 200, cùng định dạng với login. Phải lưu cả hai token mới. Lỗi 401 hoặc 403: xoá token và về màn đăng nhập. Xem [mục 3.2](#32-refresh-token-chỉ-dùng-được-một-lần).

### `POST /api/auth/logout`

Body giống `refresh`. Nên gửi kèm header `Authorization` để access token bị thu hồi ngay; nếu không gửi, token đó còn dùng được tới khi hết hạn.

Trả 204 kể cả khi token đã hết hạn hoặc sai. Client xoá token ở máy dù request thành công hay lỗi mạng.

### `POST /api/auth/forgot-password`

```json
{ "email": "an@example.com" }
```

Trả 202 kể cả khi email không tồn tại, chưa xác thực hoặc bị khoá (khi đó không gửi mail). Hãy luôn hiển thị một thông báo chung kiểu "Nếu email tồn tại, bạn sẽ nhận được mã". Lỗi: 400, hoặc 429 có `Retry-After`.

### `POST /api/auth/reset-password`

```json
{ "email": "an@example.com", "code": "012345", "newPassword": "MatKhauMoi@456" }
```

Thành công: 204, mọi thiết bị bị đăng xuất. Lỗi: 400 (mã hoặc mật khẩu không hợp lệ), 409 (request trùng), 429 (sai mã quá 5 lần).

## 6. Hồ sơ cá nhân: `/api/me`

Cần token, không cần quyền riêng.

| Endpoint | Chức năng | Thành công |
|---|---|---|
| `GET /api/me` | Xem thông tin và quyền của mình | 200 |
| `GET /api/me/auth-events` | Xem lịch sử bảo mật của mình | 200 |
| `PUT /api/me/profile` | Đổi họ tên, yêu cầu đổi email | 200 |
| `POST /api/me/profile/verify-email` | Xác nhận đổi email bằng OTP | 200 |
| `PUT /api/me/password` | Đổi mật khẩu | 204 |
| `PUT /api/me/two-factor` | Bật, tắt xác nhận 2 lớp | 200 |

### `GET /api/me`

```json
{
  "id": "158c41a0-70c8-4f5b-8b3f-18f409c3dea1",
  "email": "an@example.com",
  "fullName": "Nguyễn An",
  "status": "ACTIVE",
  "roles": ["PASSENGER"],
  "permissions": ["TICKET_BUY", "TICKET_VIEW_OWN"],
  "emailVerifiedAt": "2026-11-01T12:38:19.440394Z",
  "lastLoginAt": "2026-11-01T12:38:43.309653Z",
  "passwordChangedAt": null,
  "twoFactorEnabled": false
}
```

Ba trường thời gian có thể là `null`: chưa từng xảy ra, xảy ra trước khi có tính năng này, hoặc (`emailVerifiedAt`) tài khoản do admin tạo. `lastLoginAt` được cập nhật ngay khi login, nên nó là lần đăng nhập hiện tại, không phải lần trước đó.

Dùng `permissions` để ẩn hoặc hiện chức năng, không dựa vào tên vai trò, vì admin có thể tạo vai trò mới bất kỳ lúc nào. Backend vẫn kiểm tra quyền ở mọi API.

### `GET /api/me/auth-events`

Dùng cho màn "Hoạt động gần đây". Luôn sắp xếp mới nhất trước, nhận `page` và `size` ([cách phân trang](#get-apiadminusers)), không nhận `sort`. Lịch sử được giữ 90 ngày.

```json
{
  "content": [
    { "type": "LOGIN_SUCCESS", "ipAddress": "203.0.113.10", "userAgent": "Mozilla/5.0 …", "createdAt": "2026-11-01T12:38:43.309722Z" }
  ],
  "page": { "size": 20, "number": 0, "totalElements": 1, "totalPages": 1 }
}
```

| `type` | Ý nghĩa |
|---|---|
| `LOGIN_SUCCESS`, `LOGIN_FAILED` | Đăng nhập thành công, sai mật khẩu |
| `LOGOUT` | Đăng xuất |
| `TOKEN_REUSE_DETECTED` | Refresh token cũ bị dùng lại, phiên bị thu hồi |
| `EMAIL_VERIFIED`, `EMAIL_CHANGED` | Xác thực email, đổi email |
| `PASSWORD_CHANGED`, `PASSWORD_RESET` | Tự đổi mật khẩu, đặt lại qua quên mật khẩu |
| `TWO_FACTOR_ENABLED`, `TWO_FACTOR_DISABLED`, `TWO_FACTOR_FAILED` | Bật, tắt xác nhận 2 lớp, nhập sai mã đăng nhập |
| `ACCOUNT_CREATED_BY_ADMIN`, `PASSWORD_RESET_BY_ADMIN`, `ROLES_CHANGED`, `ACCOUNT_LOCKED`, `ACCOUNT_ACTIVATED` | Do admin thực hiện. `ipAddress` và `userAgent` là `null` |

Client tự chuyển `type` thành câu hiển thị, và phải có cách hiển thị mặc định cho `type` chưa biết. `ipAddress` có thể là dạng IPv6 như `0:0:0:0:0:0:0:1` khi chạy local. `userAgent` là chuỗi client gửi lên: trình duyệt tự có, còn app mobile nên đặt chuỗi dễ đọc, ví dụ `MetroTicket-Android/1.0`.

### `PUT /api/me/profile`

Chỉ đổi họ tên:

```json
{ "fullName": "Tên Mới" }
```

Đổi cả email:

```json
{ "fullName": "Tên Mới", "email": "moi@example.com", "currentPassword": "MatKhau@123" }
```

`fullName` luôn bắt buộc. `currentPassword` chỉ bắt buộc khi email thật sự thay đổi.

Thành công: 200, cùng định dạng với `GET /api/me`. Họ tên được đổi ngay, nhưng `email` trong response **vẫn là email cũ** cho tới khi xác nhận OTP được gửi tới email mới. Response không báo có yêu cầu đang chờ, nên sau khi gửi email mới thì client chuyển thẳng sang màn nhập OTP.

Nếu lỗi thì không có gì được lưu, kể cả họ tên.

| Lỗi | Khi nào |
|---|---|
| 400 | Dữ liệu sai, thiếu hoặc sai `currentPassword` |
| 409 | Email mới đã có người dùng |
| 429 | Chưa hết 60 giây chờ (có `Retry-After`), hoặc sai mật khẩu quá nhiều lần |

### `POST /api/me/profile/verify-email`

```json
{ "email": "moi@example.com", "code": "012345" }
```

Thành công: 200, trả thông tin với email mới, và mail thông báo được gửi tới email cũ. **Mọi token bị thu hồi ngay**, nên client xoá token và chuyển về màn đăng nhập.

| Lỗi | Khi nào |
|---|---|
| 400 | Mã sai, hết hạn, hoặc email không khớp yêu cầu mới nhất của chính người dùng |
| 409 | Email đã có người khác dùng, hoặc request trùng |
| 429 | Sai quá 5 lần, phải gửi lại `PUT /api/me/profile` |

### `PUT /api/me/password`

```json
{ "currentPassword": "MatKhau@123", "newPassword": "MatKhauMoi@456" }
```

Thành công: 204. Mọi thiết bị, kể cả thiết bị hiện tại, phải đăng nhập lại. Sai `currentPassword` trả **400** chứ không phải 401, nên sẽ không kích hoạt luồng refresh. Lỗi khác: 429 khi sai mật khẩu quá nhiều lần.

### `PUT /api/me/two-factor`

```json
{ "enabled": true, "currentPassword": "MatKhau@123" }
```

Thành công: 200, cùng định dạng với `GET /api/me`. Mặc định tắt. Các phiên đang đăng nhập vẫn giữ nguyên, lần login sau mới cần mã. Lỗi: 400 (thiếu hoặc sai `currentPassword`), 429 khi sai mật khẩu quá nhiều lần.

### Email thông báo tài khoản

Backend tự gửi, client không cần gọi gì. Mail gửi bất đồng bộ, không ảnh hưởng kết quả API.

| Sự kiện | Email | Thông báo trong app |
|---|---|---|
| Xác thực email thành công (chào mừng) | Có | Có |
| Đổi mật khẩu, đặt lại qua quên mật khẩu | Có | Có |
| Bật, tắt xác nhận 2 lớp | Có | Có |
| Đổi email | Có, gửi tới email cũ | Có |
| Admin khoá tài khoản | Có | Không |
| Admin mở khoá tài khoản | Có | Có |
| Admin tạo tài khoản, đặt lại mật khẩu | Có, kèm mật khẩu | Đặt lại mật khẩu: có |

## 7. Quản lý người dùng: `/api/admin/users`

| Endpoint | Quyền | Chức năng | Thành công |
|---|---|---|---|
| `GET /api/admin/users` | `USER_VIEW` | Danh sách, tìm kiếm | 200 |
| `GET /api/admin/users/{id}` | `USER_VIEW` | Chi tiết | 200 |
| `GET /api/admin/users/{id}/auth-events` | `USER_VIEW` | Lịch sử bảo mật | 200 |
| `GET /api/admin/auth-events` | `USER_VIEW` | Nhật ký bảo mật toàn hệ thống | 200 |
| `POST /api/admin/users` | `USER_MANAGE` | Tạo tài khoản | 201 |
| `PUT /api/admin/users/{id}/roles` | `USER_MANAGE` | Đổi vai trò | 200 |
| `PATCH /api/admin/users/{id}/status` | `USER_MANAGE` | Khoá, mở khoá | 200 |
| `PUT /api/admin/users/{id}/password` | `USER_MANAGE` | Đặt lại mật khẩu | 204 |

Các API trả về người dùng đều dùng định dạng:

```json
{
  "id": "158c41a0-70c8-4f5b-8b3f-18f409c3dea1",
  "email": "nv@example.com",
  "fullName": "Nhân Viên",
  "status": "ACTIVE",
  "roles": ["STAFF"],
  "createdAt": "2026-11-01T11:32:37.644841Z",
  "emailVerifiedAt": null,
  "lastLoginAt": "2026-11-01T12:38:43.309653Z",
  "passwordChangedAt": null
}
```

`status` là một trong `PENDING_VERIFICATION` (chưa xác thực email), `ACTIVE`, `LOCKED`.

**Quy tắc áp dụng cho các API thay đổi tài khoản:**

- Không thể tự thao tác trên tài khoản của chính mình (400). Đổi thông tin của mình thì dùng `/api/me`.
- Vai trò có `USER_MANAGE` hoặc `RBAC_MANAGE` là vai trò quản trị. Người không có `RBAC_MANAGE` không được gán vai trò đó, cũng không được thao tác trên tài khoản đang giữ vai trò đó (403). Khi tạo giao diện cho nhân viên chỉ có `USER_MANAGE`, nên xử lý trường hợp 403 này.
- `{id}` sai định dạng UUID trả 400, không tồn tại trả 404.

### `GET /api/admin/users`

| Tham số | Mặc định | Ý nghĩa |
|---|---|---|
| `q` | | Tìm một phần trong email hoặc họ tên, không phân biệt hoa thường |
| `status` | | `PENDING_VERIFICATION`, `ACTIVE` hoặc `LOCKED` |
| `page` | `0` | Trang, **bắt đầu từ 0** |
| `size` | `20` | Tối đa 100 |
| `sort` | `createdAt,asc` | `email`, `fullName`, `status` hoặc `createdAt`, kèm `,asc` hoặc `,desc`. Cột khác trả 400 |

Ví dụ: `GET /api/admin/users?q=an&status=ACTIVE&page=0&size=20&sort=createdAt,desc`

```json
{
  "content": [ { "id": "…", "email": "…", "fullName": "…", "status": "ACTIVE", "roles": ["STAFF"], "createdAt": "…" } ],
  "page": { "size": 20, "number": 0, "totalElements": 1, "totalPages": 1 }
}
```

### `GET /api/admin/users/{id}/auth-events`

Giống [`GET /api/me/auth-events`](#get-apimeauth-events), thêm `actorId` là id của admin đã thực hiện thao tác (`null` nếu chính người dùng thực hiện). Lỗi: 403, 404.

### `GET /api/admin/auth-events`

Nhật ký của mọi tài khoản, dùng cho màn "Nhật ký hệ thống". Luôn sắp xếp mới nhất trước, nhận `page` và `size`, không nhận `sort`. Lịch sử được giữ 90 ngày.

| Tham số | Ý nghĩa |
|---|---|
| `q` | Tìm trong email hoặc họ tên của tài khoản hoặc người thực hiện |
| `type` | Một `type` trong [bảng sự kiện](#get-apimeauth-events). Giá trị khác trả 400 |
| `from`, `to` | Mốc thời gian ISO-8601, ví dụ `2026-11-01T00:00:00Z`. Lấy từ `from` đến trước `to` |

```json
{
  "content": [
    {
      "id": "…",
      "type": "ACCOUNT_LOCKED",
      "user": { "id": "…", "email": "nv@example.com", "fullName": "Nhân Viên" },
      "actor": { "id": "…", "email": "admin@example.com", "fullName": "Quản trị" },
      "ipAddress": null,
      "userAgent": null,
      "createdAt": "2026-11-01T12:38:43.309722Z"
    }
  ],
  "page": { "size": 20, "number": 0, "totalElements": 1, "totalPages": 1 }
}
```

`actor` là `null` khi chính chủ thực hiện, hoặc khi tài khoản admin đó đã bị xoá. Chỉ có sự kiện của tài khoản có thật: đăng nhập sai bằng email không tồn tại không được ghi. Thay đổi vai trò và quyền trong `/api/admin/roles` hiện chưa được ghi. Lỗi: 400, 403.

### `POST /api/admin/users`

```json
{ "email": "nv@example.com", "fullName": "Nhân Viên", "roles": ["STAFF"], "temporaryPassword": "MatKhauTam@123" }
```

Bỏ hẳn `temporaryPassword` (hoặc gửi `null`) thì hệ thống tự sinh mật khẩu; chuỗi rỗng trả 400. Tài khoản được kích hoạt ngay và mật khẩu được gửi qua email.

Thành công: 201. Mail gửi bất đồng bộ, nên 201 không đảm bảo mail đã tới. Nếu người dùng không nhận được, họ dùng chức năng quên mật khẩu.

Lỗi: 400 (dữ liệu sai, vai trò không tồn tại), 403, 409 (email đã tồn tại).

### `PUT /api/admin/users/{id}/roles`

```json
{ "roles": ["STAFF"] }
```

Danh sách này **thay thế toàn bộ** vai trò hiện có và không được rỗng. Lỗi: 400, 403, 404.

### `PATCH /api/admin/users/{id}/status`

```json
{ "status": "LOCKED" }
```

Chỉ nhận `ACTIVE` hoặc `LOCKED`. Đặt `ACTIVE` cho tài khoản chưa xác thực sẽ kích hoạt tài khoản đó luôn. Lỗi: 400, 403, 404.

### `PUT /api/admin/users/{id}/password`

```json
{ "newPassword": "MatKhauMoi@456" }
```

Thành công: 204. Mật khẩu mới được gửi qua email cho người dùng, và mọi thiết bị của họ bị đăng xuất. Lỗi: 400, 403, 404.

## 8. Vai trò và quyền: `/api/admin`

Tất cả cần quyền `RBAC_MANAGE`.

| Endpoint | Chức năng | Thành công |
|---|---|---|
| `GET /api/admin/roles` | Danh sách vai trò | 200 |
| `GET /api/admin/permissions` | Danh sách quyền | 200 |
| `POST /api/admin/roles` | Tạo vai trò | 201 |
| `PUT /api/admin/roles/{code}/permissions` | Đổi quyền của vai trò | 200 |

Định dạng vai trò. `system: true` là vai trò có sẵn của hệ thống:

```json
{ "code": "STAFF", "name": "Nhân viên ga", "system": true, "permissions": ["TICKET_VALIDATE", "TICKET_VIEW_ALL", "TICKET_VIEW_OWN"] }
```

Định dạng quyền:

```json
{ "code": "USER_VIEW", "description": "Xem danh sách tài khoản" }
```

### `POST /api/admin/roles`

```json
{ "code": "AUDITOR", "name": "Kiểm toán", "permissions": ["USER_VIEW"] }
```

`code` viết hoa theo dạng `[A-Z][A-Z0-9_]{1,49}`, `name` tối đa 100 ký tự, `permissions` bắt buộc nhưng có thể là `[]`. Lỗi: 400 (dữ liệu sai, quyền không tồn tại), 409 (mã đã tồn tại).

### `PUT /api/admin/roles/{code}/permissions`

```json
{ "permissions": ["USER_VIEW", "REPORT_VIEW"] }
```

Danh sách này **thay thế toàn bộ** quyền hiện có và có hiệu lực ngay. Lỗi: 400 (quyền không tồn tại, hoặc sửa vai trò `ADMIN`), 404.

### Vai trò và quyền có sẵn

| Quyền | Mô tả | `PASSENGER` | `STAFF` | `ADMIN` |
|---|---|---|---|---|
| `TICKET_BUY` | Mua vé | ✓ | | ✓ |
| `TICKET_VIEW_OWN` | Xem vé của mình | ✓ | ✓ | ✓ |
| `TICKET_VALIDATE` | Soát vé | | ✓ | ✓ |
| `TICKET_VIEW_ALL` | Xem mọi vé | | ✓ | ✓ |
| `STATION_MANAGE` | Quản lý ga, tuyến | | | ✓ |
| `REPORT_VIEW` | Xem báo cáo | | | ✓ |
| `USER_VIEW` | Xem danh sách tài khoản | | | ✓ |
| `USER_MANAGE` | Gán vai trò, khoá/mở tài khoản | | | ✓ |
| `RBAC_MANAGE` | Quản lý vai trò và quyền | | | ✓ |
| `NOTIFICATION_MANAGE` | Gửi thông báo cho người dùng | | | ✓ |

Người tự đăng ký nhận vai trò `PASSENGER`. Danh sách này có thể thay đổi, nên giao diện quản trị phải lấy từ API.

## 9. Thông báo

Thông báo được lưu ở backend và dùng chung cho web và mobile: đọc trên một thiết bị thì thiết bị khác cũng thấy đã đọc. Được giữ 180 ngày.

**Chưa có realtime và push.** Client phải tự gọi API để cập nhật:

- Gọi `GET /api/me/notifications/unread-count` khi mở app hoặc quay lại app, và định kỳ 30–60 giây khi đang mở. Web nên dừng khi tab ẩn, mobile dừng khi app xuống nền.
- Chỉ tải danh sách khi người dùng mở màn thông báo.

| Endpoint | Quyền | Chức năng | Thành công |
|---|---|---|---|
| `GET /api/me/notifications` | Đăng nhập | Danh sách | 200 |
| `GET /api/me/notifications/unread-count` | Đăng nhập | Đếm số chưa đọc | 200 |
| `PATCH /api/me/notifications/{id}/read` | Đăng nhập | Đánh dấu đã đọc | 204 |
| `POST /api/me/notifications/read-all` | Đăng nhập | Đánh dấu đọc tất cả | 204 |
| `DELETE /api/me/notifications/{id}` | Đăng nhập | Xoá | 204 |
| `POST /api/admin/notifications` | `NOTIFICATION_MANAGE` | Gửi thông báo | 201 |

Các API trong `/api/me/notifications` chỉ thao tác trên thông báo của chính mình. Id sai định dạng trả 400; id không tồn tại hoặc của người khác trả 404. Đánh dấu đã đọc nhiều lần vẫn trả 204.

### `GET /api/me/notifications`

Luôn sắp xếp mới nhất trước, không nhận `sort`. Tham số: `page`, `size` (mặc định 20, tối đa 100) và `unreadOnly` (mặc định `false`).

```json
{
  "content": [
    {
      "id": "2ac05675-f057-4cc7-bc4a-24ae4411229d",
      "type": "ANNOUNCEMENT",
      "title": "Lịch bảo trì hệ thống",
      "body": "Nội dung thông báo",
      "read": false,
      "readAt": null,
      "createdAt": "2026-11-01T12:38:19.450192Z"
    }
  ],
  "page": { "size": 20, "number": 0, "totalElements": 3, "totalPages": 1 }
}
```

`title` và `body` là văn bản thuần, hiển thị trực tiếp, không render dạng HTML.

| `type` | Khi nào |
|---|---|
| `SYSTEM` | Chào mừng sau khi kích hoạt tài khoản, đổi vai trò |
| `SECURITY` | Đổi hoặc đặt lại mật khẩu, đổi email, mở lại tài khoản |
| `ANNOUNCEMENT` | Admin gửi |

Có thể có thêm `type` mới về sau (ví dụ về vé), nên client cần có cách hiển thị mặc định.

### `GET /api/me/notifications/unread-count`

```json
{ "count": 2 }
```

### `POST /api/admin/notifications`

Gửi cho một số người:

```json
{ "userIds": ["895bf2e0-097a-40da-8003-88509c966cc6"], "title": "Tiêu đề", "body": "Nội dung" }
```

Gửi cho tất cả tài khoản `ACTIVE`:

```json
{ "all": true, "title": "Lịch bảo trì", "body": "Hệ thống bảo trì tối nay" }
```

Phải chọn đúng một trong hai `all: true` hoặc `userIds`. `userIds` tối đa 1000, `title` tối đa 200 ký tự, `body` tối đa 2000 ký tự. Nên có bước xác nhận trước khi gửi cho tất cả, vì không có API thu hồi.

Thành công: 201.

```json
{ "recipients": 18 }
```

Lỗi: 400 (dữ liệu sai, chọn sai người nhận, có id không tồn tại), 403.

## 10. Chưa có

Những chức năng sau chưa có API, client không nên giả định là có:

- Thông báo realtime (WebSocket, SSE) và push trên mobile (FCM, APNs), kể cả đăng ký thiết bị nhận push.
- Thu hồi thông báo đã gửi, gửi thông báo qua email.
- Đăng xuất mọi thiết bị theo yêu cầu người dùng (chỉ xảy ra khi đổi mật khẩu).
- Xem danh sách thiết bị đang đăng nhập.
- Xoá người dùng; xoá hoặc đổi tên vai trò.
- API vé, ga, tuyến, báo cáo.

## 11. Thứ tự thực hiện ở frontend

Web và mobile làm theo cùng thứ tự, vì các bước sau phụ thuộc vào bước trước:

1. **Lớp gọi API:** gắn Bearer token, xử lý body rỗng và `problem+json` ([mục 2](#2-xử-lý-lỗi)), refresh token chỉ một request tại một thời điểm ([mục 3](#3-token-và-phiên-đăng-nhập)). Đây là phần dễ sai nhất, nên làm và kiểm thử trước.
2. **Đăng nhập, đăng xuất:** thử đủ 5 tài khoản mẫu ở [mục 1.1](#11-tài-khoản-để-test), gồm cả hai trường hợp 403.
3. **Đăng ký, xác thực OTP, quên mật khẩu:** có đếm ngược theo `Retry-After` ([mục 5](#5-xác-thực-apiauth)).
4. **Tải quyền sau khi đăng nhập:** gọi `GET /api/me`, dùng `permissions` để ẩn hoặc hiện chức năng.
5. **Hồ sơ cá nhân:** đổi tên, đổi mật khẩu, đổi email, hoạt động gần đây ([mục 6](#6-hồ-sơ-cá-nhân-apime)). Nhớ đăng nhập lại sau khi đổi mật khẩu hoặc email.
6. **Thông báo:** biểu tượng chuông với số chưa đọc, màn danh sách ([mục 9](#9-thông-báo)).
7. **Trang quản trị (thường chỉ trên web):** người dùng, vai trò, gửi thông báo ([mục 7](#7-quản-lý-người-dùng-apiadminusers), [mục 8](#8-vai-trò-và-quyền-apiadmin), [mục 9](#post-apiadminnotifications)).
