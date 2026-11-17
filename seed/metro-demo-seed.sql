-- MetroTicket demo seed data (plain SQL; no DO blocks or profile placeholders).
-- Run only against a local/development database after the base schema and base seed data.
-- Demo credentials are documented in seed/demo-users.example.json.
-- {noop} passwords are for local/dev demonstration only; never use these accounts in production.
-- Prerequisites: base seed must contain stations ST001/ST011, ticket product MONTH_1,
-- and parking lot PK-ST001 (the extended data also expects PK-ST008, PK-ST011, PK-ST014
-- and matching parking_rate rows, plus roles ADMIN/MANAGER/STAFF/PASSENGER).

-- Prerequisites: base seed must already contain ST001, ST011, MONTH_1 and PK-ST001.
INSERT INTO app_user (id, email, password_hash, full_name, status, created_at, email_verified_at)
    VALUES
        ('d2000000-0000-0000-0000-000000000001', 'admin.demo@metroticket.test',
         '{noop}Doi-Mat-Khau-Nay@123', 'Quản trị Demo', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
        ('d2000000-0000-0000-0000-000000000002', 'manager.demo@metroticket.test',
         '{noop}Doi-Mat-Khau-Nay@123', 'Quản lý Demo', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
        ('d2000000-0000-0000-0000-000000000003', 'staff.demo@metroticket.test',
         '{noop}Doi-Mat-Khau-Nay@123', 'Nhân viên Demo', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
        ('d2000000-0000-0000-0000-000000000004', 'passenger.demo@metroticket.test',
         '{noop}Doi-Mat-Khau-Nay@123', 'Hành khách Demo', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
        ('d2000000-0000-0000-0000-000000000005', 'pending.demo@metroticket.test',
         '{noop}Doi-Mat-Khau-Nay@123', 'Chưa xác thực Demo', 'PENDING_VERIFICATION', CURRENT_TIMESTAMP, NULL),
        ('d2000000-0000-0000-0000-000000000006', 'locked.demo@metroticket.test',
         '{noop}Doi-Mat-Khau-Nay@123', 'Bị khoá Demo', 'LOCKED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
    ON CONFLICT (email) DO NOTHING;

    INSERT INTO user_role (user_id, role_id)
    SELECT u.id, r.id
    FROM (VALUES
        ('admin.demo@metroticket.test', 'ADMIN'),
        ('manager.demo@metroticket.test', 'MANAGER'),
        ('staff.demo@metroticket.test', 'STAFF'),
        ('passenger.demo@metroticket.test', 'PASSENGER'),
        ('pending.demo@metroticket.test', 'PASSENGER'),
        ('locked.demo@metroticket.test', 'PASSENGER')
    ) AS demo(email, role_code)
    JOIN app_user u ON lower(u.email) = demo.email
    JOIN role r ON r.code = demo.role_code
    ON CONFLICT DO NOTHING;

    INSERT INTO staff_station (user_id, station_id, status)
    SELECT u.id, s.id, 'ACTIVE'
    FROM app_user u
    JOIN station s ON s.code IN ('ST001', 'ST008')
    WHERE lower(u.email) = 'staff.demo@metroticket.test'
    ON CONFLICT DO NOTHING;

    INSERT INTO ticket (
        id, ticket_code, qr_token, user_id, product_id,
        duration_unit, duration_value, periods,
        unit_price_vnd, list_unit_price_vnd, discount_code, discount_percent, price_vnd,
        status, purchased_at, valid_from, valid_until, activated_at,
        last_entry_station_id, last_entry_at, trips_count, version, created_at, updated_at
    )
    SELECT
        demo.id, demo.ticket_code, demo.qr_token, u.id, p.id,
        p.duration_unit, p.duration_value, demo.periods,
        demo.unit_price_vnd, demo.list_unit_price_vnd, demo.discount_code,
        demo.discount_percent, demo.price_vnd, demo.status,
        demo.purchased_at, demo.valid_from, demo.valid_until, demo.activated_at,
        entry_station.id, demo.last_entry_at, demo.trips_count, 0,
        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    FROM (VALUES
        (
            'd3000000-0000-0000-0000-000000000001'::uuid,
            'DEMO-TK-001', 'DEMOQR00000000000000000000000001',
            'DAY_1', 1, 40000, 40000, NULL::varchar, 0, 40000, 'ACTIVE',
            CURRENT_TIMESTAMP - INTERVAL '1 hour', CURRENT_TIMESTAMP - INTERVAL '1 hour',
            CURRENT_TIMESTAMP + INTERVAL '29 days', NULL::timestamptz,
            NULL::varchar, NULL::timestamptz, 0
        ),
        (
            'd3000000-0000-0000-0000-000000000002'::uuid,
            'DEMO-TK-002', 'DEMOQR00000000000000000000000002',
            'MONTH_1', 1, 300000, 300000, NULL::varchar, 0, 300000, 'ACTIVE',
            CURRENT_TIMESTAMP - INTERVAL '2 hours', CURRENT_TIMESTAMP - INTERVAL '2 hours',
            -- Đã quẹt vào lần đầu 2 giờ trước: hạn = activated_at + 1 tháng (Ticket.enter)
            CURRENT_TIMESTAMP - INTERVAL '2 hours' + INTERVAL '1 month', CURRENT_TIMESTAMP - INTERVAL '2 hours',
            'ST001', CURRENT_TIMESTAMP - INTERVAL '2 hours', 1
        ),
        (
            'd3000000-0000-0000-0000-000000000003'::uuid,
            'DEMO-TK-003', 'DEMOQR00000000000000000000000003',
            'DAY_3', 1, 90000, 90000, NULL::varchar, 0, 90000, 'PENDING_PAYMENT',
            CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '30 days',
            NULL::timestamptz, NULL::varchar, NULL::timestamptz, 0
        ),
        (
            'd3000000-0000-0000-0000-000000000004'::uuid,
            'DEMO-TK-004', 'DEMOQR00000000000000000000000004',
            'WEEK_1', 1, 160000, 160000, NULL::varchar, 0, 160000, 'REFUNDED',
            CURRENT_TIMESTAMP - INTERVAL '3 days', CURRENT_TIMESTAMP - INTERVAL '3 days',
            CURRENT_TIMESTAMP + INTERVAL '27 days', NULL::timestamptz,
            NULL::varchar, NULL::timestamptz, 0
        ),
        (
            'd3000000-0000-0000-0000-000000000005'::uuid,
            'DEMO-TK-005', 'DEMOQR00000000000000000000000005',
            'MONTH_1', 3, 150000, 300000, 'STUDENT', 50, 450000, 'ACTIVE',
            CURRENT_TIMESTAMP - INTERVAL '1 day', CURRENT_TIMESTAMP - INTERVAL '1 day',
            CURRENT_TIMESTAMP - INTERVAL '1 day' + INTERVAL '3 months', CURRENT_TIMESTAMP - INTERVAL '1 day',
            NULL::varchar, NULL::timestamptz, 0
        )
    ) AS demo (
        id, ticket_code, qr_token, product_code, periods,
        unit_price_vnd, list_unit_price_vnd, discount_code, discount_percent, price_vnd, status,
        purchased_at, valid_from, valid_until, activated_at,
        entry_station_code, last_entry_at, trips_count
    )
    JOIN app_user u ON lower(u.email) = 'passenger.demo@metroticket.test'
    JOIN ticket_product p ON p.code = demo.product_code
    LEFT JOIN station entry_station ON entry_station.code = demo.entry_station_code
    ON CONFLICT (ticket_code) DO NOTHING;

    UPDATE ticket
    SET refunded_at = purchased_at + INTERVAL '1 day'
    WHERE ticket_code = 'DEMO-TK-004'
      AND status = 'REFUNDED'
      AND refunded_at IS NULL;

    INSERT INTO payment_order (
        id, order_code, user_id, purpose, reference_id, amount_vnd, status,
        expires_at, paid_at, paid_amount_vnd, sepay_transaction_id, created_at, updated_at
    )
    SELECT
        demo.id, demo.order_code, u.id, 'TICKET', demo.ticket_id, demo.amount_vnd, demo.status,
        demo.expires_at, demo.paid_at, demo.paid_amount_vnd, demo.sepay_id,
        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    FROM (VALUES
        (
            'd4000000-0000-0000-0000-000000000001'::uuid,
            'MTQDEMO00000001', 'd3000000-0000-0000-0000-000000000001',
            40000, 'PAID', CURRENT_TIMESTAMP + INTERVAL '1 day',
            CURRENT_TIMESTAMP - INTERVAL '30 minutes', 40000, 990001::bigint
        ),
        (
            'd4000000-0000-0000-0000-000000000002'::uuid,
            'MTQDEMO00000002', 'd3000000-0000-0000-0000-000000000003',
            90000, 'PENDING', CURRENT_TIMESTAMP + INTERVAL '15 minutes',
            NULL::timestamptz, NULL::integer, 990002::bigint
        )
    ) AS demo(id, order_code, ticket_id, amount_vnd, status, expires_at, paid_at, paid_amount_vnd, sepay_id)
    JOIN app_user u ON lower(u.email) = 'passenger.demo@metroticket.test'
    ON CONFLICT DO NOTHING;

    INSERT INTO payment_transaction (
        id, user_id, purpose, reference_id, amount_vnd, method, status, provider_ref, created_at, refunded_at
    )
    SELECT
        demo.id, u.id, 'TICKET', demo.ticket_id, demo.amount_vnd,
        demo.method, demo.status, demo.provider_ref, demo.created_at, demo.refunded_at
    FROM (VALUES
        (
            'd5000000-0000-0000-0000-000000000001'::uuid,
            'd3000000-0000-0000-0000-000000000001', 40000, 'SEPAY_QR', 'SUCCEEDED',
            'DEMO-FT-001', CURRENT_TIMESTAMP - INTERVAL '30 minutes', NULL::timestamptz
        ),
        (
            'd5000000-0000-0000-0000-000000000002'::uuid,
            'd3000000-0000-0000-0000-000000000002', 300000, 'CARD', 'SUCCEEDED',
            'DEMO-CARD-002', CURRENT_TIMESTAMP - INTERVAL '2 hours', NULL::timestamptz
        ),
        (
            'd5000000-0000-0000-0000-000000000003'::uuid,
            'd3000000-0000-0000-0000-000000000004', 160000, 'E_WALLET', 'REFUNDED',
            'DEMO-WALLET-004', CURRENT_TIMESTAMP - INTERVAL '3 days',
            CURRENT_TIMESTAMP - INTERVAL '2 days'
        ),
        (
            'd5000000-0000-0000-0000-000000000005'::uuid,
            'd3000000-0000-0000-0000-000000000005', 450000, 'CARD', 'SUCCEEDED',
            'DEMO-CARD-005', CURRENT_TIMESTAMP - INTERVAL '1 day', NULL::timestamptz
        )
    ) AS demo(id, ticket_id, amount_vnd, method, status, provider_ref, created_at, refunded_at)
    JOIN app_user u ON lower(u.email) = 'passenger.demo@metroticket.test'
    ON CONFLICT (id) DO NOTHING;

    INSERT INTO sepay_transaction (
        id, gateway, transaction_date, account_number, code, content,
        transfer_type, transfer_amount, reference_code, description,
        order_id, result, note, received_at
    )
    VALUES
        (990001, 'DEMO_BANK', CURRENT_TIMESTAMP::text, '0123456789', 'MTQDEMO00000001',
         'Thanh toan don MTQDEMO00000001', 'in', 40000, 'DEMO-FT-001',
         'Giao dịch demo đã khớp', 'd4000000-0000-0000-0000-000000000001',
         'MATCHED', 'Demo', CURRENT_TIMESTAMP),
        (990002, 'DEMO_BANK', CURRENT_TIMESTAMP::text, '0123456789', 'MTQDEMO00000002',
         'Thanh toan don MTQDEMO00000002', 'in', 50000, 'DEMO-FT-002',
         'Giao dịch demo chuyển thiếu tiền', 'd4000000-0000-0000-0000-000000000002',
         'AMOUNT_MISMATCH', 'Demo', CURRENT_TIMESTAMP)
    ON CONFLICT (id) DO NOTHING;

    INSERT INTO student_verification (
        id, user_id, status, declared_full_name, declared_student_code, declared_school,
        image_mime_type, ai_attempts, ai_is_student_card, ai_full_name, ai_student_code,
        ai_school, ai_valid_until, ai_confidence, ai_tampering_suspected,
        decision_source, decision_reasons, reviewed_by, review_note, approved_until,
        submitted_at, decided_at, version
    )
    SELECT
        demo.id, u.id, demo.status, demo.full_name, demo.student_code, demo.school,
        'image/png', 1, TRUE, demo.ai_name, demo.student_code, demo.school,
        CURRENT_DATE + 180, 0.96, FALSE,
        demo.decision_source, demo.reason, reviewer.id, demo.review_note, demo.approved_until,
        CURRENT_TIMESTAMP - INTERVAL '2 days', demo.decided_at, 0
    FROM (VALUES
        (
            'd6000000-0000-0000-0000-000000000001'::uuid,
            'passenger.demo@metroticket.test', 'APPROVED',
            'Hành khách Demo', 'SV2026001', 'Đại học Bách khoa',
            'HANH KHACH DEMO', 'MANUAL', 'Hồ sơ demo đã được duyệt',
            'Đã kiểm tra hồ sơ demo', CURRENT_DATE + 180, CURRENT_TIMESTAMP - INTERVAL '1 day'
        ),
        (
            'd6000000-0000-0000-0000-000000000002'::uuid,
            'pending.demo@metroticket.test', 'NEEDS_REVIEW',
            'Chưa xác thực Demo', 'SV2026002', 'Đại học Kinh tế',
            'CHUA XAC THUC DEMO', NULL::varchar, 'Cần nhân viên kiểm tra',
            NULL::varchar, NULL::date, NULL::timestamptz
        )
    ) AS demo(id, email, status, full_name, student_code, school, ai_name,
              decision_source, reason, review_note, approved_until, decided_at)
    JOIN app_user u ON lower(u.email) = demo.email
    LEFT JOIN app_user reviewer ON lower(reviewer.email) = 'admin.demo@metroticket.test'
    ON CONFLICT (id) DO NOTHING;

    -- Tiny valid placeholder image for UI rendering; not a real student-card image.
    INSERT INTO student_verification_image (verification_id, content)
    VALUES
        ('d6000000-0000-0000-0000-000000000001',
         decode('89504E470D0A1A0A0000000D4948445200000001000000010802000000907753DE0000000C49444154789C6378F1E93F00059F02DA0F28B8F70000000049454E44AE426082', 'hex')),
        ('d6000000-0000-0000-0000-000000000002',
         decode('89504E470D0A1A0A0000000D4948445200000001000000010802000000907753DE0000000C49444154789C6378F1E93F00059F02DA0F28B8F70000000049454E44AE426082', 'hex'))
    ON CONFLICT (verification_id) DO NOTHING;

    INSERT INTO parking_monthly_pass (
        id, pass_code, user_id, lot_id, vehicle_type, plate_number,
        price_vnd, valid_from, valid_until, status, created_at, updated_at
    )
    SELECT
        'd7000000-0000-0000-0000-000000000001', 'DEMO-PASS-001', u.id, pl.id,
        'MOTORBIKE', '59X112345', 120000,
        CURRENT_TIMESTAMP - INTERVAL '5 days', CURRENT_TIMESTAMP + INTERVAL '25 days',
        'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    FROM app_user u
    JOIN parking_lot pl ON pl.code = 'PK-ST001'
    WHERE lower(u.email) = 'passenger.demo@metroticket.test'
    ON CONFLICT DO NOTHING;

    INSERT INTO parking_session (
        id, session_code, lot_id, vehicle_type, plate_number, monthly_pass_id,
        status, check_in_at, check_out_at, fee_vnd, discount_vnd, payment_method,
        checked_in_by, checked_out_by, created_at, updated_at
    )
    SELECT
        demo.id, demo.session_code, pl.id, demo.vehicle_type, demo.plate_number,
        demo.monthly_pass_id, demo.status, demo.check_in_at, demo.check_out_at,
        demo.fee_vnd, demo.discount_vnd, demo.payment_method,
        staff.id, CASE WHEN demo.status = 'COMPLETED' THEN staff.id ELSE NULL END,
        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    FROM (VALUES
        (
            'd8000000-0000-0000-0000-000000000001'::uuid,
            'DEMO-PS-001', 'PK-ST001', 'MOTORBIKE', '59X112345',
            'd7000000-0000-0000-0000-000000000001'::uuid, 'PARKED',
            CURRENT_TIMESTAMP - INTERVAL '1 hour', NULL::timestamptz,
            NULL::integer, NULL::integer, NULL::varchar
        ),
        (
            'd8000000-0000-0000-0000-000000000002'::uuid,
            'DEMO-PS-002', 'PK-ST008', 'CAR', '51A12345',
            NULL::uuid, 'COMPLETED', CURRENT_TIMESTAMP - INTERVAL '4 hours',
            CURRENT_TIMESTAMP - INTERVAL '2 hours', 25000, 0, 'CASH'
        )
    ) AS demo(id, session_code, lot_code, vehicle_type, plate_number, monthly_pass_id,
              status, check_in_at, check_out_at, fee_vnd, discount_vnd, payment_method)
    JOIN parking_lot pl ON pl.code = demo.lot_code
    JOIN app_user staff ON lower(staff.email) = 'staff.demo@metroticket.test'
    ON CONFLICT DO NOTHING;

    INSERT INTO ticket_validation (
        ticket_id, station_id, direction, result, reason, validated_by, validated_at
    )
    SELECT t.id, s.id, demo.direction, 'ACCEPTED', NULL, staff.id, demo.validated_at
    FROM (VALUES
        ('ST001', 'ENTRY', CURRENT_TIMESTAMP - INTERVAL '2 hours'),
        ('ST011', 'EXIT', CURRENT_TIMESTAMP - INTERVAL '1 hour 35 minutes')
    ) AS demo(station_code, direction, validated_at)
    JOIN ticket t ON t.ticket_code = 'DEMO-TK-002'
    JOIN station s ON s.code = demo.station_code
    JOIN app_user staff ON lower(staff.email) = 'staff.demo@metroticket.test'
    WHERE NOT EXISTS (
        SELECT 1 FROM ticket_validation tv
        WHERE tv.ticket_id = t.id
          AND tv.station_id = s.id
          AND tv.direction = demo.direction
          AND tv.result = 'ACCEPTED'
    );

    INSERT INTO green_trip (
        user_id, ticket_id, entry_station_id, exit_station_id,
        entry_at, exit_at, distance_km, co2_saved_grams, points, counted
    )
    SELECT
        u.id, t.id, entry_station.id, exit_station.id,
        CURRENT_TIMESTAMP - INTERVAL '2 hours',
        CURRENT_TIMESTAMP - INTERVAL '1 hour 35 minutes',
        13.422, 805, 134, TRUE
    FROM app_user u
    JOIN ticket t ON t.ticket_code = 'DEMO-TK-002'
    JOIN station entry_station ON entry_station.code = 'ST001'
    JOIN station exit_station ON exit_station.code = 'ST011'
    WHERE lower(u.email) = 'passenger.demo@metroticket.test'
      AND NOT EXISTS (
          SELECT 1 FROM green_trip gt
          WHERE gt.ticket_id = t.id
            AND gt.entry_station_id = entry_station.id
            AND gt.exit_station_id = exit_station.id
      );

    INSERT INTO announcement (
        line_id, title, content, severity, starts_at, ends_at, status, scope,
        created_by, submitted_at, approved_by, approved_at, created_at, updated_at
    )
    SELECT
        ml.id, 'Thông báo demo: điều chỉnh giờ cao điểm',
        'Thông báo mẫu để trình diễn màn hình thông tin vận hành.',
        'WARNING', CURRENT_TIMESTAMP - INTERVAL '1 hour',
        CURRENT_TIMESTAMP + INTERVAL '7 days', 'APPROVED', 'LINE',
        staff.id, CURRENT_TIMESTAMP - INTERVAL '2 hours',
        admin.id, CURRENT_TIMESTAMP - INTERVAL '1 hour',
        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    FROM metro_line ml
    JOIN app_user staff ON lower(staff.email) = 'staff.demo@metroticket.test'
    JOIN app_user admin ON lower(admin.email) = 'admin.demo@metroticket.test'
    WHERE ml.code = 'BTS-01'
      AND NOT EXISTS (
          SELECT 1 FROM announcement a
          WHERE a.title = 'Thông báo demo: điều chỉnh giờ cao điểm'
      );

    INSERT INTO notification (
        id, user_id, type, title, body, read_at, created_at, priority, link, campaign_id, dedupe_key
    )
    SELECT
        demo.id, u.id, demo.type, demo.title, demo.body, demo.read_at,
        CURRENT_TIMESTAMP, demo.priority, demo.link, NULL, demo.dedupe_key
    FROM (VALUES
        (
            'd9000000-0000-0000-0000-000000000001'::uuid,
            'PAYMENT', 'Thanh toán vé thành công',
            'Đơn thanh toán demo đã được xác nhận.',
            NULL::timestamptz, 'HIGH', '/tickets', 'DEMO_PAYMENT_001'
        ),
        (
            'd9000000-0000-0000-0000-000000000002'::uuid,
            'GREEN_JOURNEY', 'Bạn vừa có một hành trình xanh',
            'Chuyến đi demo đã giúp tiết kiệm khoảng 805 g CO2.',
            CURRENT_TIMESTAMP - INTERVAL '1 day', 'NORMAL', '/green-journey', 'DEMO_GREEN_001'
        )
    ) AS demo(id, type, title, body, read_at, priority, link, dedupe_key)
    JOIN app_user u ON lower(u.email) = 'passenger.demo@metroticket.test'
    ON CONFLICT (id) DO NOTHING;

INSERT INTO app_user (id, email, password_hash, full_name, status, created_at, email_verified_at)
    SELECT
        md5('metroticket-extended-demo-user-' || n)::uuid,
        'demo.passenger.' || lpad(n::text, 2, '0') || '@metroticket.test',
        '{noop}Doi-Mat-Khau-Nay@123',
        'Khách Demo ' || lpad(n::text, 2, '0'),
        'ACTIVE',
        CURRENT_TIMESTAMP - (n * INTERVAL '1 day'),
        CURRENT_TIMESTAMP - (n * INTERVAL '1 day')
    FROM generate_series(1, 12) AS n
    ON CONFLICT (email) DO NOTHING;

    INSERT INTO user_role (user_id, role_id)
    SELECT u.id, r.id
    FROM app_user u
    JOIN role r ON r.code = 'PASSENGER'
    WHERE u.email LIKE 'demo.passenger.__@metroticket.test'
    ON CONFLICT DO NOTHING;

    INSERT INTO auth_event (id, user_id, actor_id, type, ip_address, user_agent, created_at)
    SELECT
        md5('metroticket-extended-demo-auth-event-' || u.id::text)::uuid,
        u.id,
        admin.id,
        'ACCOUNT_CREATED_BY_ADMIN',
        '127.0.0.1',
        'MetroTicket demo seed',
        u.created_at
    FROM app_user u
    JOIN app_user admin ON lower(admin.email) = 'admin.demo@metroticket.test'
    WHERE u.email LIKE 'demo.passenger.__@metroticket.test'
    ON CONFLICT (id) DO NOTHING;

    WITH generated AS (
        SELECT
            n,
            ((n - 1) / 3) + 1 AS user_number,
            CASE n % 4
                WHEN 0 THEN 'DAY_1'
                WHEN 1 THEN 'DAY_3'
                WHEN 2 THEN 'WEEK_1'
                ELSE 'MONTH_1'
            END AS product_code,
            CASE
                WHEN n % 12 = 0 THEN 'PENDING_PAYMENT'
                WHEN n % 12 = 1 THEN 'REFUNDED'
                WHEN n % 12 = 2 THEN 'EXPIRED'
                WHEN n % 5 = 0 THEN 'IN_USE'
                ELSE 'ACTIVE'
            END AS ticket_status
        FROM generate_series(1, 36) AS n
    ), ticket_data AS (
        SELECT
            g.*,
            CASE
                WHEN ticket_status = 'EXPIRED' THEN CURRENT_TIMESTAMP - INTERVAL '60 days'
                WHEN ticket_status = 'REFUNDED' THEN CURRENT_TIMESTAMP - INTERVAL '7 days'
                WHEN ticket_status = 'PENDING_PAYMENT' THEN CURRENT_TIMESTAMP
                ELSE CURRENT_TIMESTAMP - INTERVAL '2 hours'
            END AS purchased_at
        FROM generated g
    )
    INSERT INTO ticket (
        id, ticket_code, qr_token, user_id, product_id,
        duration_unit, duration_value, periods,
        unit_price_vnd, list_unit_price_vnd, discount_code, discount_percent, price_vnd,
        status, purchased_at, valid_from, valid_until, activated_at,
        last_entry_station_id, last_entry_at, trips_count, version, created_at, updated_at, refunded_at
    )
    SELECT
        md5('metroticket-extended-demo-ticket-' || d.n)::uuid,
        'DEMO-EXT-TK-' || lpad(d.n::text, 3, '0'),
        md5('metroticket-extended-demo-qr-' || d.n),
        u.id,
        p.id,
        p.duration_unit,
        p.duration_value,
        1,
        p.price_vnd,
        p.price_vnd,
        NULL,
        0,
        p.price_vnd,
        d.ticket_status,
        d.purchased_at,
        d.purchased_at,
        -- Chưa kích hoạt: hạn = hạn chót kích hoạt (activation_window_days, như TicketService);
        -- đã quẹt vào (IN_USE): hạn = activated_at + thời lượng (như Ticket.enter)
        CASE WHEN d.ticket_status = 'IN_USE'
            THEN d.purchased_at + INTERVAL '30 minutes' + CASE p.duration_unit
                WHEN 'DAY' THEN p.duration_value * INTERVAL '1 day'
                WHEN 'WEEK' THEN p.duration_value * INTERVAL '1 week'
                ELSE p.duration_value * INTERVAL '1 month'
            END
            ELSE d.purchased_at + p.activation_window_days * INTERVAL '1 day'
        END,
        CASE WHEN d.ticket_status = 'IN_USE' THEN d.purchased_at + INTERVAL '30 minutes' END,
        CASE WHEN d.ticket_status = 'IN_USE' THEN entry_station.id END,
        CASE WHEN d.ticket_status = 'IN_USE' THEN d.purchased_at + INTERVAL '30 minutes' END,
        0,
        0,
        d.purchased_at,
        d.purchased_at,
        CASE WHEN d.ticket_status = 'REFUNDED' THEN d.purchased_at + INTERVAL '1 day' END
    FROM ticket_data d
    JOIN app_user u ON u.email = 'demo.passenger.' || lpad(d.user_number::text, 2, '0') || '@metroticket.test'
    JOIN ticket_product p ON p.code = d.product_code
    LEFT JOIN station entry_station ON entry_station.code = 'ST001'
    ON CONFLICT (ticket_code) DO NOTHING;

    INSERT INTO payment_transaction (
        id, user_id, purpose, reference_id, amount_vnd, method, status,
        provider_ref, created_at, refunded_at
    )
    SELECT
        md5('metroticket-extended-demo-payment-' || t.ticket_code)::uuid,
        t.user_id,
        'TICKET',
        t.id::text,
        t.price_vnd,
        CASE mod(substring(t.ticket_code FROM '[0-9]+$')::int, 3)
            WHEN 0 THEN 'SEPAY_QR'
            WHEN 1 THEN 'CARD'
            ELSE 'E_WALLET'
        END,
        CASE WHEN t.status = 'REFUNDED' THEN 'REFUNDED' ELSE 'SUCCEEDED' END,
        'DEMO-EXT-' || t.ticket_code,
        t.purchased_at,
        t.refunded_at
    FROM ticket t
    WHERE t.ticket_code LIKE 'DEMO-EXT-TK-%'
      AND t.status <> 'PENDING_PAYMENT'
    ON CONFLICT (id) DO NOTHING;

    INSERT INTO payment_order (
        id, order_code, user_id, purpose, reference_id, amount_vnd, status,
        expires_at, sepay_transaction_id, created_at, updated_at
    )
    SELECT
        md5('metroticket-extended-demo-order-' || t.ticket_code)::uuid,
        'MTQEXT' || lpad(substring(t.ticket_code FROM '[0-9]+$'), 8, '0'),
        t.user_id,
        'TICKET',
        t.id::text,
        t.price_vnd,
        'PENDING',
        CURRENT_TIMESTAMP + INTERVAL '15 minutes',
        993000 + substring(t.ticket_code FROM '[0-9]+$')::bigint,
        CURRENT_TIMESTAMP,
        CURRENT_TIMESTAMP
    FROM ticket t
    WHERE t.ticket_code LIKE 'DEMO-EXT-TK-%'
      AND t.status = 'PENDING_PAYMENT'
    ON CONFLICT DO NOTHING;

    INSERT INTO sepay_transaction (
        id, gateway, transaction_date, account_number, code, content,
        transfer_type, transfer_amount, reference_code, description,
        order_id, result, note, received_at
    )
    SELECT
        993000 + substring(t.ticket_code FROM '[0-9]+$')::bigint,
        'DEMO_BANK',
        CURRENT_TIMESTAMP::text,
        '0123456789',
        po.order_code,
        'Thanh toan don ' || po.order_code,
        'in',
        t.price_vnd - 1000,
        'DEMO-EXT-FT-' || t.ticket_code,
        'Giao dịch demo chuyển thiếu tiền',
        po.id,
        'AMOUNT_MISMATCH',
        'Demo local',
        CURRENT_TIMESTAMP
    FROM ticket t
    JOIN payment_order po ON po.reference_id = t.id::text AND po.purpose = 'TICKET'
    WHERE t.ticket_code LIKE 'DEMO-EXT-TK-%'
      AND t.status = 'PENDING_PAYMENT'
    ON CONFLICT (id) DO NOTHING;

    INSERT INTO ticket_validation (
        ticket_id, station_id, direction, result, reason, validated_by, validated_at
    )
    SELECT
        t.id, s.id, 'ENTRY', 'ACCEPTED', NULL, staff.id, t.last_entry_at
    FROM ticket t
    JOIN station s ON s.code = 'ST001'
    JOIN app_user staff ON lower(staff.email) = 'staff.demo@metroticket.test'
    WHERE t.ticket_code LIKE 'DEMO-EXT-TK-%'
      AND t.status = 'IN_USE'
      AND NOT EXISTS (
          SELECT 1 FROM ticket_validation tv
          WHERE tv.ticket_id = t.id AND tv.direction = 'ENTRY' AND tv.result = 'ACCEPTED'
      );

    WITH selected AS (
        SELECT t.*
        FROM ticket t
        WHERE t.ticket_code LIKE 'DEMO-EXT-TK-%'
          AND t.status = 'ACTIVE'
          AND substring(t.ticket_code FROM '[0-9]+$')::int % 6 IN (1, 3)
    )
    -- Nhóm %6=3: chuyến ST001 -> ST011 (35 phút, được tính điểm);
    -- nhóm %6=1: chuyến ST001 -> ST002 chỉ 1 phút (không tính điểm). Khớp với green_trip bên dưới.
    INSERT INTO ticket_validation (
        ticket_id, station_id, direction, result, reason, validated_by, validated_at
    )
    SELECT s.id, st.id, v.direction, 'ACCEPTED', NULL, staff.id, v.validated_at
    FROM selected s
    JOIN app_user staff ON lower(staff.email) = 'staff.demo@metroticket.test'
    CROSS JOIN LATERAL (
        VALUES
            ('ST001', 'ENTRY', s.purchased_at + INTERVAL '30 minutes'),
            (CASE WHEN substring(s.ticket_code FROM '[0-9]+$')::int % 6 = 3 THEN 'ST011' ELSE 'ST002' END,
             'EXIT',
             s.purchased_at + CASE WHEN substring(s.ticket_code FROM '[0-9]+$')::int % 6 = 3
                 THEN INTERVAL '65 minutes' ELSE INTERVAL '31 minutes' END)
    ) AS v(station_code, direction, validated_at)
    JOIN station st ON st.code = v.station_code
    WHERE NOT EXISTS (
        SELECT 1 FROM ticket_validation tv
        WHERE tv.ticket_id = s.id
          AND tv.station_id = st.id
          AND tv.direction = v.direction
          AND tv.result = 'ACCEPTED'
    );

    WITH selected AS (
        SELECT t.*
        FROM ticket t
        WHERE t.ticket_code LIKE 'DEMO-EXT-TK-%'
          AND t.status = 'ACTIVE'
          AND substring(t.ticket_code FROM '[0-9]+$')::int % 6 = 3
    )
    INSERT INTO green_trip (
        user_id, ticket_id, entry_station_id, exit_station_id,
        entry_at, exit_at, distance_km, co2_saved_grams, points, counted
    )
    SELECT
        t.user_id,
        t.id,
        entry_station.id,
        exit_station.id,
        t.purchased_at + INTERVAL '30 minutes',
        t.purchased_at + INTERVAL '65 minutes',
        13.422,
        805,
        134,
        TRUE
    FROM selected t
    JOIN station entry_station ON entry_station.code = 'ST001'
    JOIN station exit_station ON exit_station.code = 'ST011'
    WHERE NOT EXISTS (
        SELECT 1 FROM green_trip gt
        WHERE gt.ticket_id = t.id
          AND gt.entry_station_id = entry_station.id
          AND gt.exit_station_id = exit_station.id
    );

    WITH selected AS (
        SELECT t.*
        FROM ticket t
        WHERE t.ticket_code LIKE 'DEMO-EXT-TK-%'
          AND t.status = 'ACTIVE'
          AND substring(t.ticket_code FROM '[0-9]+$')::int % 6 = 1
    )
    INSERT INTO green_trip (
        user_id, ticket_id, entry_station_id, exit_station_id,
        entry_at, exit_at, distance_km, co2_saved_grams, points, counted, not_counted_reason
    )
    SELECT
        t.user_id,
        t.id,
        entry_station.id,
        exit_station.id,
        t.purchased_at + INTERVAL '30 minutes',
        t.purchased_at + INTERVAL '31 minutes',
        0.700,
        0,
        0,
        FALSE,
        -- Cùng câu chữ với GreenJourneyService.calculate (min_trip_minutes mặc định = 2)
        'Chuyến đi ngắn hơn 2 phút'
    FROM selected t
    JOIN station entry_station ON entry_station.code = 'ST001'
    JOIN station exit_station ON exit_station.code = 'ST002'
    WHERE NOT EXISTS (
        SELECT 1 FROM green_trip gt
        WHERE gt.ticket_id = t.id
          AND gt.entry_station_id = entry_station.id
          AND gt.exit_station_id = exit_station.id
    );

    -- Vé đã có lượt vào/ra phải ở trạng thái đã kích hoạt, nếu không Ticket.isUnused()
    -- coi là vé chưa dùng và vẫn cho hoàn tiền.
    UPDATE ticket t
    SET activated_at = t.purchased_at + INTERVAL '30 minutes',
        last_entry_station_id = (SELECT id FROM station WHERE code = 'ST001'),
        last_entry_at = t.purchased_at + INTERVAL '30 minutes',
        trips_count = 1,
        valid_until = t.purchased_at + INTERVAL '30 minutes' + CASE t.duration_unit
            WHEN 'DAY' THEN t.duration_value * INTERVAL '1 day'
            WHEN 'WEEK' THEN t.duration_value * INTERVAL '1 week'
            ELSE t.duration_value * INTERVAL '1 month'
        END
    WHERE t.ticket_code LIKE 'DEMO-EXT-TK-%'
      AND t.status = 'ACTIVE'
      AND t.activated_at IS NULL
      AND EXISTS (
          SELECT 1 FROM ticket_validation tv
          WHERE tv.ticket_id = t.id AND tv.direction = 'EXIT' AND tv.result = 'ACCEPTED'
      );

    INSERT INTO parking_monthly_pass (
        id, pass_code, user_id, lot_id, vehicle_type, plate_number,
        price_vnd, valid_from, valid_until, status, created_at, updated_at
    )
    SELECT
        md5('metroticket-extended-demo-pass-' || n)::uuid,
        'DEMO-EXT-PASS-' || lpad(n::text, 3, '0'),
        u.id,
        pl.id,
        'MOTORBIKE',
        '59X1D' || lpad(n::text, 5, '0'),
        rate.monthly_price_vnd,
        CURRENT_TIMESTAMP - INTERVAL '5 days',
        CURRENT_TIMESTAMP + INTERVAL '25 days',
        'ACTIVE',
        CURRENT_TIMESTAMP,
        CURRENT_TIMESTAMP
    FROM generate_series(1, 6) AS n
    JOIN app_user u ON u.email = 'demo.passenger.' || lpad(n::text, 2, '0') || '@metroticket.test'
    JOIN parking_lot pl ON pl.code = CASE mod(n - 1, 4)
        WHEN 0 THEN 'PK-ST001'
        WHEN 1 THEN 'PK-ST008'
        WHEN 2 THEN 'PK-ST011'
        ELSE 'PK-ST014'
    END
    JOIN parking_rate rate ON rate.lot_id = pl.id AND rate.vehicle_type = 'MOTORBIKE'
    ON CONFLICT (pass_code) DO NOTHING;

    INSERT INTO parking_session (
        id, session_code, lot_id, vehicle_type, plate_number, monthly_pass_id,
        status, check_in_at, check_out_at, fee_vnd, discount_vnd, payment_method,
        checked_in_by, checked_out_by, created_at, updated_at
    )
    SELECT
        md5('metroticket-extended-demo-session-' || n)::uuid,
        'DEMO-EXT-PS-' || lpad(n::text, 3, '0'),
        pl.id,
        CASE WHEN n <= 6 THEN 'MOTORBIKE'
             WHEN mod(n, 3) = 0 THEN 'CAR'
             WHEN mod(n, 3) = 1 THEN 'BICYCLE'
             ELSE 'MOTORBIKE' END,
        CASE WHEN n <= 6 THEN '59X1D' || lpad(n::text, 5, '0')
             WHEN mod(n, 3) = 1 THEN NULL
             ELSE '51D' || lpad(n::text, 5, '0') END,
        CASE WHEN n <= 6 THEN md5('metroticket-extended-demo-pass-' || n)::uuid END,
        CASE WHEN n = 6 THEN 'PARKED' ELSE 'COMPLETED' END,
        CASE WHEN n = 6 THEN CURRENT_TIMESTAMP - INTERVAL '1 hour'
             ELSE CURRENT_TIMESTAMP - (n + 2) * INTERVAL '1 hour' END,
        CASE WHEN n = 6 THEN NULL
             ELSE CURRENT_TIMESTAMP - (n + 1) * INTERVAL '1 hour' END,
        CASE WHEN n = 6 THEN NULL
             WHEN n <= 6 THEN 0
             ELSE rate.day_price_vnd END,
        CASE WHEN n = 6 THEN NULL ELSE 0 END,
        CASE WHEN n <= 6 THEN NULL ELSE 'CASH' END,
        staff.id,
        CASE WHEN n = 6 THEN NULL ELSE staff.id END,
        CURRENT_TIMESTAMP,
        CURRENT_TIMESTAMP
    FROM generate_series(1, 12) AS n
    JOIN parking_lot pl ON pl.code = CASE mod(n - 1, 4)
        WHEN 0 THEN 'PK-ST001'
        WHEN 1 THEN 'PK-ST008'
        WHEN 2 THEN 'PK-ST011'
        ELSE 'PK-ST014'
    END
    JOIN parking_rate rate ON rate.lot_id = pl.id
        AND rate.vehicle_type = CASE WHEN n <= 6 THEN 'MOTORBIKE'
            WHEN mod(n, 3) = 0 THEN 'CAR'
            WHEN mod(n, 3) = 1 THEN 'BICYCLE'
            ELSE 'MOTORBIKE' END
    JOIN app_user staff ON lower(staff.email) = 'staff.demo@metroticket.test'
    ON CONFLICT DO NOTHING;

    INSERT INTO student_verification (
        id, user_id, status, declared_full_name, declared_student_code, declared_school,
        image_mime_type, ai_attempts, ai_is_student_card, ai_full_name, ai_student_code,
        ai_school, ai_valid_until, ai_confidence, ai_tampering_suspected,
        decision_source, decision_reasons, reviewed_by, review_note, approved_until,
        submitted_at, decided_at, version
    )
    SELECT
        md5('metroticket-extended-demo-student-' || n)::uuid,
        u.id,
        CASE n WHEN 1 THEN 'APPROVED' WHEN 2 THEN 'REJECTED' ELSE 'NEEDS_REVIEW' END,
        u.full_name,
        'DEMO-SV-' || lpad(n::text, 4, '0'),
        CASE WHEN mod(n, 2) = 0 THEN 'Đại học Kinh tế' ELSE 'Đại học Bách khoa' END,
        'image/png',
        1,
        TRUE,
        upper(u.full_name),
        'DEMO-SV-' || lpad(n::text, 4, '0'),
        CASE WHEN mod(n, 2) = 0 THEN 'Đại học Kinh tế' ELSE 'Đại học Bách khoa' END,
        CURRENT_DATE + 180,
        0.94,
        FALSE,
        CASE WHEN n <= 2 THEN 'MANUAL' END,
        CASE WHEN n = 1 THEN 'Hồ sơ demo đã được duyệt'
             WHEN n = 2 THEN 'Hồ sơ demo bị từ chối'
             ELSE 'Cần nhân viên xem xét hồ sơ demo' END,
        CASE WHEN n <= 2 THEN reviewer.id END,
        CASE WHEN n <= 2 THEN 'Dữ liệu minh họa' END,
        CASE WHEN n = 1 THEN CURRENT_DATE + 180 END,
        CURRENT_TIMESTAMP - INTERVAL '2 days',
        CASE WHEN n <= 2 THEN CURRENT_TIMESTAMP - INTERVAL '1 day' END,
        0
    FROM generate_series(1, 4) AS n
    JOIN app_user u ON u.email = 'demo.passenger.' || lpad((n + 6)::text, 2, '0') || '@metroticket.test'
    JOIN app_user reviewer ON lower(reviewer.email) = 'admin.demo@metroticket.test'
    ON CONFLICT (id) DO NOTHING;

    INSERT INTO student_verification_image (verification_id, content)
    SELECT
        md5('metroticket-extended-demo-student-' || n)::uuid,
        decode('89504E470D0A1A0A0000000D4948445200000001000000010802000000907753DE0000000C49444154789C6378F1E93F00059F02DA0F28B8F70000000049454E44AE426082', 'hex')
    FROM generate_series(1, 4) AS n
    ON CONFLICT (verification_id) DO NOTHING;

    INSERT INTO operation_config (
        line_id, version, number_of_trains, headway_minutes, running_time_minutes,
        turnaround_time_minutes, start_time, end_time, effective_from, effective_to,
        status, created_by, submitted_at, approved_by, approved_at, rejection_reason,
        created_at, updated_at
    )
    SELECT
        ml.id,
        latest.version + demo.version_offset,
        demo.number_of_trains,
        demo.headway_minutes,
        demo.running_time_minutes,
        demo.turnaround_time_minutes,
        TIME '05:00:00',
        TIME '22:00:00',
        CURRENT_TIMESTAMP - INTERVAL '1 day',
        NULL,
        demo.status,
        admin.id,
        CASE WHEN demo.status <> 'DRAFT' THEN CURRENT_TIMESTAMP - INTERVAL '2 hours' END,
        CASE WHEN demo.status IN ('APPROVED', 'REJECTED') THEN manager.id END,
        CASE WHEN demo.status = 'APPROVED' THEN CURRENT_TIMESTAMP - INTERVAL '1 hour' END,
        CASE WHEN demo.status = 'REJECTED' THEN 'Dữ liệu demo: cần điều chỉnh headway' END,
        CURRENT_TIMESTAMP,
        CURRENT_TIMESTAMP
    FROM (VALUES
        (1, 5, 14, 30, 5, 'DRAFT'),
        (2, 5, 12, 32, 5, 'PENDING_APPROVAL'),
        (3, 5, 10, 30, 5, 'APPROVED'),
        (4, 4, 8, 28, 5, 'REJECTED')
    ) AS demo(version_offset, number_of_trains, headway_minutes, running_time_minutes,
              turnaround_time_minutes, status)
    JOIN metro_line ml ON ml.code = 'BTS-01'
    JOIN app_user admin ON lower(admin.email) = 'admin.demo@metroticket.test'
    JOIN app_user manager ON lower(manager.email) = 'manager.demo@metroticket.test'
    CROSS JOIN LATERAL (
        SELECT COALESCE(MAX(oc.version), 0) AS version
        FROM operation_config oc
        WHERE oc.line_id = ml.id
    ) latest
    -- version = MAX + offset nên không bao giờ trùng; chặn bằng NOT EXISTS để chạy lại không sinh thêm bản ghi
    WHERE NOT EXISTS (
        SELECT 1 FROM operation_config oc
        WHERE oc.line_id = ml.id AND oc.created_by = admin.id
    )
    ON CONFLICT DO NOTHING;

    INSERT INTO ticket_product_audit (product_id, action, changed_by, changes, changed_at)
    SELECT p.id, demo.action, admin.id, demo.changes, CURRENT_TIMESTAMP
    FROM (VALUES
        ('DAY_1', 'CREATED', 'Loại vé ngày mẫu được khởi tạo.'),
        ('WEEK_1', 'UPDATED', 'Cập nhật mô tả loại vé tuần cho dữ liệu demo.'),
        ('MONTH_1', 'UPDATED', 'Cập nhật mức giá vé tháng cho dữ liệu demo.')
    ) AS demo(product_code, action, changes)
    JOIN ticket_product p ON p.code = demo.product_code
    JOIN app_user admin ON lower(admin.email) = 'admin.demo@metroticket.test'
    WHERE NOT EXISTS (
        SELECT 1 FROM ticket_product_audit a
        WHERE a.product_id = p.id AND a.action = demo.action AND a.changes = demo.changes
    );

    INSERT INTO announcement (
        line_id, title, content, severity, starts_at, ends_at, status, scope,
        created_by, submitted_at, approved_by, approved_at, created_at, updated_at
    )
    SELECT
        ml.id,
        demo.title,
        demo.content,
        demo.severity,
        CURRENT_TIMESTAMP - INTERVAL '1 hour',
        CURRENT_TIMESTAMP + INTERVAL '7 days',
        demo.status,
        'LINE',
        staff.id,
        CASE WHEN demo.status <> 'DRAFT' THEN CURRENT_TIMESTAMP - INTERVAL '2 hours' END,
        CASE WHEN demo.status = 'APPROVED' THEN admin.id END,
        CASE WHEN demo.status = 'APPROVED' THEN CURRENT_TIMESTAMP - INTERVAL '1 hour' END,
        CURRENT_TIMESTAMP,
        CURRENT_TIMESTAMP
    FROM (VALUES
        ('Lưu ý giờ cao điểm - Demo', 'Thông báo demo về lưu lượng hành khách giờ cao điểm.', 'INFO', 'APPROVED'),
        ('Bảo trì thang cuốn - Demo', 'Thông báo demo về kế hoạch bảo trì tại một số nhà ga.', 'WARNING', 'DRAFT')
    ) AS demo(title, content, severity, status)
    JOIN metro_line ml ON ml.code = 'BTS-01'
    JOIN app_user staff ON lower(staff.email) = 'staff.demo@metroticket.test'
    JOIN app_user admin ON lower(admin.email) = 'admin.demo@metroticket.test'
    WHERE NOT EXISTS (
        SELECT 1 FROM announcement a WHERE a.title = demo.title
    );

    INSERT INTO notification_preference (user_id, push_enabled, email_enabled, promotion_enabled, reminder_enabled)
    SELECT
        u.id,
        TRUE,
        TRUE,
        mod(substring(u.email FROM '[0-9]+')::int, 2) = 0,
        TRUE
    FROM app_user u
    WHERE u.email LIKE 'demo.passenger.__@metroticket.test'
    ON CONFLICT (user_id) DO NOTHING;

    INSERT INTO notification (
        id, user_id, type, title, body, read_at, created_at, priority, link, campaign_id, dedupe_key
    )
    SELECT
        md5('metroticket-extended-demo-notification-' || u.id::text || '-' || n)::uuid,
        u.id,
        CASE n WHEN 1 THEN 'TICKET' ELSE 'PROMOTION' END,
        CASE n WHEN 1 THEN 'Vé Metro đã sẵn sàng' ELSE 'Ưu đãi dành cho bạn' END,
        CASE n WHEN 1 THEN 'Vé demo đã được thêm vào tài khoản của bạn.'
             ELSE 'Đây là thông báo khuyến mãi mẫu để trình diễn.' END,
        CASE WHEN mod(substring(u.email FROM '[0-9]+')::int + n, 2) = 0
             THEN CURRENT_TIMESTAMP - INTERVAL '1 day' END,
        CURRENT_TIMESTAMP - (n * INTERVAL '1 hour'),
        CASE WHEN n = 1 THEN 'HIGH' ELSE 'NORMAL' END,
        CASE WHEN n = 1 THEN '/tickets' ELSE '/promotions' END,
        NULL,
        'DEMO_EXT:' || u.id::text || ':' || n
    FROM app_user u
    CROSS JOIN generate_series(1, 2) AS n
    WHERE u.email LIKE 'demo.passenger.__@metroticket.test'
    ON CONFLICT (id) DO NOTHING;

    INSERT INTO notification_campaign (
        id, title, body, type, priority, link, audience_type, audience_value,
        channel_push, channel_email, status, scheduled_at, started_at, completed_at,
        recipient_count, push_queued, email_queued, source,
        created_by, updated_by, sent_by, created_at, updated_at, version
    )
    SELECT
        md5('metroticket-extended-demo-campaign-' || demo.code)::uuid,
        demo.title,
        demo.body,
        demo.type,
        demo.priority,
        demo.link,
        demo.audience_type,
        demo.audience_value,
        demo.channel_push,
        demo.channel_email,
        demo.status,
        demo.scheduled_at,
        demo.started_at,
        demo.completed_at,
        demo.recipient_count,
        demo.push_queued,
        demo.email_queued,
        'ADMIN',
        admin.id,
        admin.id,
        CASE WHEN demo.status = 'SENT' THEN admin.id END,
        CURRENT_TIMESTAMP,
        CURRENT_TIMESTAMP,
        0
    FROM (VALUES
        ('SENT', 'Thông tin vận hành Metro - Demo', 'Thông báo mẫu đã gửi tới hành khách.',
         'ANNOUNCEMENT', 'HIGH', '/announcements', 'ALL', NULL::varchar, FALSE, TRUE, 'SENT',
         CURRENT_TIMESTAMP - INTERVAL '2 days', CURRENT_TIMESTAMP - INTERVAL '2 days',
         CURRENT_TIMESTAMP - INTERVAL '2 days', 16, 0, 16),
        ('SCHEDULED', 'Ưu đãi vé tháng - Demo', 'Chiến dịch mẫu sắp được gửi tới hành khách.',
         'PROMOTION', 'NORMAL', '/tickets', 'ROLE', 'PASSENGER', TRUE, FALSE, 'SCHEDULED',
         CURRENT_TIMESTAMP + INTERVAL '1 day', NULL::timestamptz, NULL::timestamptz, 0, 0, 0),
        ('DRAFT', 'Khảo sát hành khách Demo', 'Chiến dịch nháp gửi một nhóm hành khách mẫu.',
         'SYSTEM', 'NORMAL', '/profile', 'USERS', NULL::varchar, FALSE, FALSE, 'DRAFT',
         NULL::timestamptz, NULL::timestamptz, NULL::timestamptz, 0, 0, 0)
    ) AS demo(code, title, body, type, priority, link, audience_type, audience_value,
              channel_push, channel_email, status, scheduled_at, started_at, completed_at,
              recipient_count, push_queued, email_queued)
    JOIN app_user admin ON lower(admin.email) = 'admin.demo@metroticket.test'
    WHERE NOT EXISTS (
        SELECT 1 FROM notification_campaign c WHERE c.id = md5('metroticket-extended-demo-campaign-' || demo.code)::uuid
    );

    INSERT INTO notification_campaign_user (campaign_id, user_id)
    SELECT
        md5('metroticket-extended-demo-campaign-DRAFT')::uuid,
        u.id
    FROM app_user u
    WHERE u.email IN (
        'demo.passenger.01@metroticket.test',
        'demo.passenger.02@metroticket.test'
    )
    ON CONFLICT DO NOTHING;

    INSERT INTO notification (
        id, user_id, type, title, body, read_at, created_at, priority, link, campaign_id, dedupe_key
    )
    SELECT
        md5('metroticket-demo-campaign-notification-' || u.id::text)::uuid,
        u.id,
        'ANNOUNCEMENT',
        c.title,
        c.body,
        CURRENT_TIMESTAMP - INTERVAL '1 day',
        CURRENT_TIMESTAMP - INTERVAL '2 days',
        c.priority,
        c.link,
        c.id,
        'DEMO_CAMPAIGN:' || c.id::text
    FROM notification_campaign c
    -- Chỉ gửi cho tài khoản demo: không chèn thông báo demo vào hộp thư của người dùng thật
    JOIN app_user u ON u.status = 'ACTIVE' AND u.email LIKE '%@metroticket.test'
    WHERE c.id = md5('metroticket-extended-demo-campaign-SENT')::uuid
      AND NOT EXISTS (
          SELECT 1 FROM notification n
          WHERE n.user_id = u.id AND n.campaign_id = c.id
      )
    ON CONFLICT (id) DO NOTHING;

    INSERT INTO notification_delivery (
        notification_id, user_id, channel, status, attempts,
        next_attempt_at, sent_at, created_at
    )
    SELECT
        n.id,
        n.user_id,
        'EMAIL',
        'SENT',
        1,
        n.created_at,
        n.created_at,
        n.created_at
    FROM notification n
    WHERE n.campaign_id = md5('metroticket-extended-demo-campaign-SENT')::uuid
      AND NOT EXISTS (
          SELECT 1 FROM notification_delivery d
          WHERE d.notification_id = n.id AND d.channel = 'EMAIL'
      );
