package com.nocook.service.ingestion;

import com.nocook.domain.model.CancellationEvent;
import com.nocook.domain.oms.OmsView;
import com.nocook.service.application.CaseOpeningService;
import com.nocook.service.application.CaseOpeningService.OpenResult;
import com.nocook.service.observability.NocookMeters;
import com.nocook.service.oms.OmsReadPort;
import com.nocook.service.oms.OmsUnavailableException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * BACKEND.md section 3.1. Not transactional itself: the OMS read happens outside the DB transaction, and
 * {@link CaseOpeningService#open} commits before this method returns (so the after-commit measurements are taken here).
 * Any exception propagates to the AMQP container, which requeues (delivery limit 10, then DLQ).
 */
@Component
public class CancellationIngestor {

    private static final Logger log = LoggerFactory.getLogger(CancellationIngestor.class);

    private final OmsReadPort omsRead;
    private final CaseOpeningService caseOpening;
    private final NocookMeters meters;
    private final Clock clock;

    public CancellationIngestor(
            OmsReadPort omsRead, CaseOpeningService caseOpening, NocookMeters meters, Clock clock) {
        this.omsRead = omsRead;
        this.caseOpening = caseOpening;
        this.meters = meters;
        this.clock = clock;
    }

    /** {@code publishedAt} comes from the x-published-at header; null when absent or unparsable. */
    public OpenResult ingest(CancellationEvent event, @Nullable Instant publishedAt) {
        if (publishedAt != null) {
            meters.consumeLag(Duration.between(publishedAt, clock.instant()));
        }
        OmsView view = omsRead.get(event.orderId());
        if (!(view instanceof OmsView.Available available)) {
            String reason = view instanceof OmsView.Unavailable unavailable ? unavailable.reason() : "NOT_READ";
            meters.eventConsumed("oms_unavailable");
            throw new OmsUnavailableException(event.orderId(), reason);
        }
        OpenResult result = caseOpening.open(event, available.snapshot());
        recordOutcome(event, result);
        return result;
    }

    private void recordOutcome(CancellationEvent event, OpenResult result) {
        switch (result.outcome()) {
            case OPENED -> {
                meters.eventConsumed("opened");
                meters.caseOpened();
                meters.caseOpenLatency(Duration.between(event.cancelledAt(), clock.instant()));
            }
            case NOT_ACTIONABLE -> meters.eventConsumed("not_actionable");
            case DUPLICATE -> meters.eventConsumed("duplicate");
        }
        log.info("cancellation handled eventId={} orderId={} caseId={} outcome={}",
                event.eventId(), event.orderId(), result.caseId(), result.outcome());
    }
}
