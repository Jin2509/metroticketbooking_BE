package com.metroticket_api.ticket;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.metroticket_api.security.CurrentUser;
import com.metroticket_api.ticket.TicketProductAudit.Action;
import com.metroticket_api.ticket.TicketProductDtos.AuditResponse;
import com.metroticket_api.ticket.TicketProductDtos.CreateProductRequest;
import com.metroticket_api.ticket.TicketProductDtos.ProductResponse;
import com.metroticket_api.ticket.TicketProductDtos.PublicProductResponse;
import com.metroticket_api.ticket.TicketProductDtos.UpdateProductRequest;

/**
 * Quản lý loại vé theo thời gian (quyền FARE_MANAGE).
 *
 * <ul>
 *   <li><b>Thêm</b>: mã duy nhất (viết hoa); thời lượng hợp lệ theo đơn vị (DAY 1–6, WEEK 1–3, MONTH 1–12); chỉ vé
 *       tháng được bán nhiều kỳ (tối đa 12); không có 2 loại vé ACTIVE trùng thời lượng.</li>
 *   <li><b>Sửa</b>: mã không đổi; phải gửi đúng {@code version} đang có (chống ghi đè khi 2 người cùng sửa);
 *       đổi giá phải qua bảng giá chờ duyệt (TicketPriceService), vé đã bán giữ giá cũ;
 *       thời lượng chỉ đổi được khi loại vé chưa bán vé nào.</li>
 *   <li><b>Ngừng bán / mở bán</b>: vé đã bán vẫn dùng bình thường; mở bán lại kiểm tra trùng thời lượng.</li>
 *   <li><b>Xoá</b>: chỉ khi chưa bán vé nào (kể cả vé đã huỷ/hoàn), ngược lại phải ngừng bán.</li>
 *   <li>Mọi thay đổi ghi vào ticket_product_audit: ai, lúc nào, giá trị cũ → mới.</li>
 * </ul>
 */
@Service
public class TicketProductService {

    private final TicketProductRepository products;
    private final TicketRepository tickets;
    private final TicketProductAuditRepository audits;
    private final Clock clock;

    public TicketProductService(TicketProductRepository products, TicketRepository tickets,
            TicketProductAuditRepository audits, Clock clock) {
        this.products = products;
        this.tickets = tickets;
        this.audits = audits;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<PublicProductResponse> listActive() {
        return products.findByStatusOrderByIdAsc(ProductStatus.ACTIVE).stream().map(PublicProductResponse::of).toList();
    }

    @Transactional(readOnly = true)
    public List<ProductResponse> listAll() {
        return products.findAllByOrderByIdAsc().stream()
                .map(p -> ProductResponse.of(p, tickets.existsByProductId(p.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public ProductResponse get(Long id) {
        TicketProduct p = require(id);
        return ProductResponse.of(p, tickets.existsByProductId(id));
    }

    @Transactional(readOnly = true)
    public List<AuditResponse> history(Long id) {
        return audits.findByProductIdOrderByChangedAtDescIdDesc(id).stream().map(AuditResponse::of).toList();
    }

    @Transactional
    public ProductResponse create(CreateProductRequest request) {
        String code = request.code().trim().toUpperCase(Locale.ROOT);
        if (products.existsByCode(code)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Mã loại vé " + code + " đã tồn tại");
        }
        int maxPeriods = request.maxPeriods() == null ? 1 : request.maxPeriods();
        int window = request.activationWindowDays() == null ? 30 : request.activationWindowDays();
        UUID actor = CurrentUser.optionalId().orElse(null);
        TicketProduct product = new TicketProduct(code, request.name().trim(), blankToNull(request.description()),
                request.durationUnit(), request.durationValue(), request.priceVnd(), maxPeriods, window,
                request.status(), actor);
        validate(product);
        if (product.getStatus() == ProductStatus.ACTIVE) {
            ensureNoActiveDuplicate(product);
        }
        products.saveAndFlush(product);
        audit(product, Action.CREATED, actor, "code=" + code + ", thời lượng=" + product.durationLabel()
                + ", giá=" + product.getPriceVnd() + ", tối đa " + maxPeriods + " kỳ, trạng thái=" + product.getStatus());
        return ProductResponse.of(product, false);
    }

    @Transactional
    public ProductResponse update(Long id, UpdateProductRequest request) {
        TicketProduct product = require(id);
        ensureVersion(product, request.version());
        boolean sold = tickets.existsByProductId(id);
        List<String> changes = new ArrayList<>();

        if (request.name() != null && !request.name().isBlank() && !request.name().trim().equals(product.getName())) {
            changes.add("tên: " + product.getName() + " → " + request.name().trim());
            product.setName(request.name().trim());
        }
        if (request.description() != null && !Objects.equals(blankToNull(request.description()), product.getDescription())) {
            changes.add("mô tả");
            product.setDescription(blankToNull(request.description()));
        }
        DurationUnit unit = request.durationUnit() == null ? product.getDurationUnit() : request.durationUnit();
        int value = request.durationValue() == null ? product.getDurationValue() : request.durationValue();
        if (unit != product.getDurationUnit() || value != product.getDurationValue()) {
            if (sold) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Loại vé đã bán, không thể đổi thời lượng; hãy ngừng bán và tạo loại vé mới");
            }
            if (product.isSingleJourney()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Vé lượt không đổi được thời lượng");
            }
            String before = product.durationLabel();
            product.setDuration(unit, value);
            changes.add("thời lượng: " + before + " → " + product.durationLabel());
            if (!unit.allowsMultiplePeriods() && request.maxPeriods() == null) {
                product.setMaxPeriods(1);
            }
        }
        if (request.priceVnd() != null && request.priceVnd() != product.getPriceVnd()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Đổi giá phải lập bảng giá và được Quản lý duyệt");
        }
        if (request.maxPeriods() != null && request.maxPeriods() != product.getMaxPeriods()) {
            changes.add("số kỳ tối đa: " + product.getMaxPeriods() + " → " + request.maxPeriods());
            product.setMaxPeriods(request.maxPeriods());
        }
        if (request.activationWindowDays() != null && request.activationWindowDays() != product.getActivationWindowDays()) {
            changes.add("hạn kích hoạt: " + product.getActivationWindowDays() + " → " + request.activationWindowDays() + " ngày");
            product.setActivationWindowDays(request.activationWindowDays());
        }
        if (changes.isEmpty()) {
            return ProductResponse.of(product, sold);
        }
        validate(product);
        if (product.getStatus() == ProductStatus.ACTIVE) {
            ensureNoActiveDuplicate(product);
        }
        UUID actor = CurrentUser.optionalId().orElse(null);
        product.setUpdatedBy(actor);
        products.saveAndFlush(product);
        audit(product, Action.UPDATED, actor, String.join("; ", changes));
        return ProductResponse.of(product, sold);
    }

    @Transactional
    public ProductResponse activate(Long id) {
        TicketProduct product = require(id);
        if (product.getStatus() == ProductStatus.ACTIVE) {
            return ProductResponse.of(product, tickets.existsByProductId(id));
        }
        ensureNoActiveDuplicate(product);
        return changeStatus(product, ProductStatus.ACTIVE, Action.ACTIVATED);
    }

    /** Ngừng bán: không mua mới được, vé đã bán và đơn đang chờ thanh toán vẫn được giữ nguyên. */
    @Transactional
    public ProductResponse deactivate(Long id) {
        TicketProduct product = require(id);
        if (product.getStatus() == ProductStatus.INACTIVE) {
            return ProductResponse.of(product, tickets.existsByProductId(id));
        }
        return changeStatus(product, ProductStatus.INACTIVE, Action.DEACTIVATED);
    }

    @Transactional
    public void delete(Long id) {
        TicketProduct product = require(id);
        if (tickets.existsByProductId(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Loại vé đã có vé được bán, không thể xoá; hãy ngừng bán (deactivate)");
        }
        audit(product, Action.DELETED, CurrentUser.optionalId().orElse(null),
                "code=" + product.getCode() + ", " + product.durationLabel() + ", giá=" + product.getPriceVnd());
        products.delete(product);
    }

    private ProductResponse changeStatus(TicketProduct product, ProductStatus status, Action action) {
        UUID actor = CurrentUser.optionalId().orElse(null);
        product.setStatus(status);
        product.setUpdatedBy(actor);
        products.saveAndFlush(product);
        audit(product, action, actor, "trạng thái → " + status);
        return ProductResponse.of(product, tickets.existsByProductId(product.getId()));
    }

    static void validate(TicketProduct p) {
        if (!p.getDurationUnit().acceptsValue(p.getDurationValue())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Thời lượng " + p.getDurationValue() + " "
                    + p.getDurationUnit() + " không hợp lệ (tối đa " + p.getDurationUnit().maxValue() + ")");
        }
        if (p.getMaxPeriods() < 1 || p.getMaxPeriods() > DurationUnit.MAX_PERIODS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Số kỳ tối đa phải từ 1 đến " + DurationUnit.MAX_PERIODS);
        }
        if (p.getMaxPeriods() > 1 && !p.getDurationUnit().allowsMultiplePeriods()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Chỉ vé tháng được mua nhiều kỳ một lần");
        }
        if (p.getDurationUnit() == DurationUnit.MONTH && p.getDurationValue() * p.getMaxPeriods() > DurationUnit.MAX_PERIODS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tổng thời hạn một lần mua không được quá 12 tháng");
        }
        if ((long) p.getPriceVnd() * p.getMaxPeriods() > Integer.MAX_VALUE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Giá × số kỳ vượt giới hạn");
        }
    }

    private void ensureNoActiveDuplicate(TicketProduct product) {
        if (product.isSingleJourney()) {
            return;
        }
        products.findByDurationUnitAndDurationValueAndStatusAndSingleJourneyFalse(product.getDurationUnit(), product.getDurationValue(),
                ProductStatus.ACTIVE)
                .filter(other -> !other.getId().equals(product.getId()))
                .ifPresent(other -> {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Đang bán loại vé " + other.getCode()
                            + " cùng thời lượng " + product.durationLabel() + "; hãy ngừng bán loại đó trước");
                });
    }

    private static void ensureVersion(TicketProduct product, Long expected) {
        if (expected == null || expected != product.getVersion()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Loại vé vừa được người khác thay đổi, hãy tải lại trước khi sửa");
        }
    }

    private TicketProduct require(Long id) {
        return products.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy loại vé"));
    }

    private void audit(TicketProduct product, Action action, UUID actor, String changes) {
        audits.save(new TicketProductAudit(product.getId(), action, actor, changes, clock.instant()));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
