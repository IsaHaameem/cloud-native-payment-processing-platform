package com.paymentflow.payment.service;

import com.paymentflow.common.dto.page.CursorPage;
import com.paymentflow.common.exception.ResourceNotFoundException;
import com.paymentflow.common.query.Cursor;
import com.paymentflow.common.query.CursorCodec;
import com.paymentflow.common.query.ListQuery;
import com.paymentflow.payment.domain.Payment;
import com.paymentflow.payment.domain.Refund;
import com.paymentflow.payment.dto.PaymentListFilter;
import com.paymentflow.payment.dto.PaymentResponse;
import com.paymentflow.payment.dto.RefundListFilter;
import com.paymentflow.payment.dto.RefundResponse;
import com.paymentflow.payment.mapper.PaymentMapper;
import com.paymentflow.payment.repository.PaymentRepository;
import com.paymentflow.payment.repository.RefundRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The public read surface for payments and refunds (M19.2/M19.3).
 *
 * <p>Separate from {@link PaymentService} deliberately: that class owns the FSM,
 * idempotency, the outbox, and the merchant/sandbox calls, and none of that is involved
 * in answering a question. Keeping reads out of it means M19 adds no new way to reach the
 * mutation path — the read surface literally cannot transition a payment, because it
 * holds nothing that could.
 *
 * <p>Every method takes {@code merchantId} and {@code mode} from the caller (resolved at
 * the web layer from the verified context) and passes both into scoped repository
 * methods. A payment belonging to another merchant, or to the other mode, resolves to
 * empty and surfaces as 404 — never 403, which would confirm it exists (D102).
 */
@Service
@Transactional(readOnly = true)
public class PaymentQueryService {

    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final PaymentMapper mapper;
    private final CursorCodec cursorCodec;

    public PaymentQueryService(PaymentRepository paymentRepository, RefundRepository refundRepository,
                               PaymentMapper mapper, CursorCodec cursorCodec) {
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
        this.mapper = mapper;
        this.cursorCodec = cursorCodec;
    }

    public PaymentResponse getPayment(UUID merchantId, String mode, UUID paymentId, boolean expandRefunds) {
        Payment payment = paymentRepository.findByIdAndMerchantIdAndMode(paymentId, merchantId, mode)
                .orElseThrow(() -> ResourceNotFoundException.of("Payment", paymentId));
        PaymentResponse response = mapper.toResponse(payment);
        if (!expandRefunds) {
            return response;
        }
        return response.withRefunds(refundRepository.findByPaymentIdOrderByCreatedAtAsc(paymentId).stream()
                .map(mapper::toResponse)
                .toList());
    }

    public CursorPage<PaymentResponse> listPayments(UUID merchantId, String mode, ListQuery query,
                                                    PaymentListFilter filter, boolean expandRefunds) {
        List<Payment> fetched = paymentRepository.findPage(
                merchantId, mode,
                filter.status(), filter.currency(), filter.amountMin(), filter.amountMax(),
                // Bounds, never nulls: the null-guarded form demotes these from index
                // conditions to filters — see PaymentRepository.findPage's javadoc for
                // the measurement.
                query.createdAfterBound(), query.createdBeforeBound(), filter.metadataJson(),
                query.cursorCreatedAtBound(), query.cursorIdBound(),
                query.fetchSize());

        // Trim the over-fetched row *before* expanding, so a page of 25 never triggers 26
        // refund lookups — and so the extra row's refunds are never read at all.
        CursorPage<Payment> page = CursorPage.of(fetched, query.limit(),
                payment -> encode(payment.getCreatedAt(), payment.getId(), merchantId, mode));

        List<PaymentResponse> data = expandRefunds
                ? withRefunds(page.data())
                : page.data().stream().map(mapper::toResponse).toList();

        return new CursorPage<>(CursorPage.OBJECT_TYPE, data, page.hasMore(), page.nextCursor());
    }

    public RefundResponse getRefund(UUID merchantId, String mode, UUID refundId) {
        return refundRepository.findByIdAndMerchantIdAndMode(refundId, merchantId, mode)
                .map(mapper::toResponse)
                .orElseThrow(() -> ResourceNotFoundException.of("Refund", refundId));
    }

    public CursorPage<RefundResponse> listRefunds(UUID merchantId, String mode, ListQuery query,
                                                  RefundListFilter filter) {
        List<Refund> fetched = refundRepository.findPage(
                merchantId, mode, filter.paymentId(), filter.status(),
                query.createdAfterBound(), query.createdBeforeBound(), filter.metadataJson(),
                query.cursorCreatedAtBound(), query.cursorIdBound(),
                query.fetchSize());

        CursorPage<Refund> page = CursorPage.of(fetched, query.limit(),
                refund -> encode(refund.getCreatedAt(), refund.getId(), merchantId, mode));

        return new CursorPage<>(CursorPage.OBJECT_TYPE,
                page.data().stream().map(mapper::toResponse).toList(), page.hasMore(), page.nextCursor());
    }

    /** One query for the whole page's refunds rather than one per payment. */
    private List<PaymentResponse> withRefunds(List<Payment> payments) {
        if (payments.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<RefundResponse>> byPayment =
                refundRepository.findByPaymentIdInOrderByCreatedAtAsc(payments.stream().map(Payment::getId).toList())
                        .stream()
                        .collect(Collectors.groupingBy(Refund::getPaymentId,
                                Collectors.mapping(mapper::toResponse, Collectors.toList())));

        return payments.stream()
                .map(payment -> mapper.toResponse(payment)
                        .withRefunds(byPayment.getOrDefault(payment.getId(), List.of())))
                .toList();
    }

    private String encode(java.time.Instant createdAt, UUID id, UUID merchantId, String mode) {
        return cursorCodec.encode(new Cursor(createdAt, id, merchantId, mode));
    }
}
