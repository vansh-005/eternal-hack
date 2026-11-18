package com.nocook.oms.testsupport;

import com.nocook.oms.orders.OrderModel.CancelInitiator;
import com.nocook.oms.orders.OrderModel.Stage;
import com.nocook.oms.orders.OrderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * F7: per order, create ACCEPTED (60 s old), mark READY, wait gapMillis, cancel by CUSTOMER. READY always commits
 * before the cancel, so NoCook must classify each as NOT_ACTIONABLE / READY_OR_PICKED_UP.
 */
@RestController
@RequestMapping("/oms/api/test/race")
public class RaceController {

    public record RaceRequest(
            @Min(1) @Max(5_000) int count, @NotBlank String outletId, @Min(0) @Max(10_000) long gapMillis) {}

    public record RaceResponse(List<String> orderIds) {}

    private static final int MAX_CONCURRENT = 12; // stays under the Hikari pool (20)

    private final OrderService orderService;

    public RaceController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping("/ready-then-cancel")
    public RaceResponse readyThenCancel(@Valid @RequestBody RaceRequest request) {
        Semaphore permits = new Semaphore(MAX_CONCURRENT);
        List<Future<String>> futures = new ArrayList<>(request.count());
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < request.count(); i++) {
                futures.add(executor.submit(() -> {
                    permits.acquire();
                    try {
                        String orderId = orderService
                                .createAccepted(request.outletId(), Stage.PREP, 60, 45_000, null)
                                .orderId();
                        orderService.ready(orderId);
                        if (request.gapMillis() > 0) {
                            Thread.sleep(request.gapMillis());
                        }
                        orderService.cancel(orderId, CancelInitiator.CUSTOMER);
                        return orderId;
                    } finally {
                        permits.release();
                    }
                }));
            }
        }
        List<String> orderIds = new ArrayList<>(request.count());
        for (Future<String> future : futures) {
            try {
                orderIds.add(future.get());
            } catch (ExecutionException ex) {
                throw new IllegalStateException("race order failed: " + ex.getCause(), ex.getCause());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("race interrupted", ex);
            }
        }
        return new RaceResponse(orderIds);
    }
}
