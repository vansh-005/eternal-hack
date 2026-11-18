package com.nocook.service.telephony;

import com.nocook.service.config.WorkerProperties;
import com.nocook.service.persistence.CallAttemptRepository;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Mock provider adapter. {@code placeCall} only mints a providerCallId; the tablet user (or, for load outlets, an
 * auto-outcome script on a virtual thread) then drives status/DTMF through the signed webhooks. Script shares
 * (LOAD_AND_CHAOS.md section 4): 75 % ring, answer, DTMF, complete; 15 % ring then no answer at 30 s; 10 % busy.
 */
@Component
public class MockTelephonyGateway implements TelephonyGateway {

    private static final Logger log = LoggerFactory.getLogger(MockTelephonyGateway.class);

    private final MockProvider provider;
    private final CallAttemptRepository callAttempts;
    private final WorkerProperties.Telephony telephony;
    private final ConcurrentHashMap<String, Thread> scripts = new ConcurrentHashMap<>();

    public MockTelephonyGateway(
            MockProvider provider, CallAttemptRepository callAttempts, WorkerProperties.Telephony telephony) {
        this.provider = provider;
        this.callAttempts = callAttempts;
        this.telephony = telephony;
    }

    @Override
    public CallResult placeCall(CallRequest request) {
        String providerCallId = "mock-" + UUID.randomUUID();
        if (request.outletId().startsWith(telephony.autoOutcomeOutletPrefix())) {
            Thread script = Thread.startVirtualThread(() -> playScript(providerCallId));
            scripts.put(providerCallId, script);
        }
        return new CallResult(providerCallId);
    }

    @Override
    public void hangup(String providerCallId) {
        Thread script = scripts.remove(providerCallId);
        if (script != null) {
            script.interrupt();
        }
    }

    private void playScript(String providerCallId) {
        try {
            int roll = ThreadLocalRandom.current().nextInt(100);
            if (roll < 75) {
                step(providerCallId, 1000, () -> provider.emitStatus(providerCallId, "RINGING"));
                step(providerCallId, 3000, () -> provider.emitStatus(providerCallId, "ANSWERED"));
                step(providerCallId, 4000, () -> provider.emitDtmf(providerCallId, randomDigit()));
                step(providerCallId, 1000, () -> provider.emitStatus(providerCallId, "COMPLETED"));
            } else if (roll < 90) {
                step(providerCallId, 1000, () -> provider.emitStatus(providerCallId, "RINGING"));
                step(providerCallId, 29000, () -> provider.emitStatus(providerCallId, "NO_ANSWER"));
            } else {
                step(providerCallId, 3000, () -> provider.emitStatus(providerCallId, "BUSY"));
            }
        } catch (InterruptedException ex) {
            log.debug("auto-outcome script stopped providerCallId={}", providerCallId);
        } finally {
            scripts.remove(providerCallId);
        }
    }

    /** Sleep (scaled), stop if the call already ended (checked in the DB: any replica may have hung up). */
    private void step(String providerCallId, long delayMillis, Runnable action) throws InterruptedException {
        Thread.sleep(Math.max(1, (long) (delayMillis * telephony.autoOutcomeTimeScale())));
        boolean ended = callAttempts.findByProviderCallId(providerCallId, false)
                .map(CallAttemptRepository.CallAttempt::isTerminal)
                .orElse(false);
        if (ended) {
            throw new InterruptedException("call ended");
        }
        action.run();
    }

    private static String randomDigit() {
        int roll = ThreadLocalRandom.current().nextInt(100);
        if (roll < 80) {
            return "1";
        }
        return roll < 90 ? "2" : "3";
    }
}
