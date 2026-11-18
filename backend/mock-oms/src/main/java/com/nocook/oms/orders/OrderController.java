package com.nocook.oms.orders;

import com.nocook.oms.orders.OrderModel.CancelInitiator;
import com.nocook.oms.orders.OrderModel.OrderItem;
import com.nocook.oms.orders.OrderModel.Stage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/oms/api")
public class OrderController {

    public static final String RUN_ID_HEADER = "X-Run-Id";

    public record ItemRequest(@NotBlank String name, @Min(1) int qty, @PositiveOrZero long pricePaise) {}

    public record CreateOrderRequest(@NotBlank String outletId, @NotEmpty List<@Valid ItemRequest> items) {}

    public record StageRequest(@NotNull Stage stage) {}

    public record CancelRequest(@Nullable CancelInitiator initiatedBy) {}

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public OrderSnapshot create(
            @Valid @RequestBody CreateOrderRequest request,
            @RequestHeader(value = RUN_ID_HEADER, required = false) @Nullable String runId) {
        List<OrderItem> items = request.items().stream()
                .map(item -> new OrderItem(item.name(), item.qty(), item.pricePaise()))
                .toList();
        return orderService.create(request.outletId(), items, runId);
    }

    @PostMapping("/orders/{id}/accept")
    public OrderSnapshot accept(@PathVariable("id") String orderId) {
        return orderService.accept(orderId);
    }

    @PostMapping("/orders/{id}/stage")
    public OrderSnapshot stage(@PathVariable("id") String orderId, @Valid @RequestBody StageRequest request) {
        return orderService.setStage(orderId, request.stage());
    }

    @PostMapping("/orders/{id}/ready")
    public OrderSnapshot ready(@PathVariable("id") String orderId) {
        return orderService.ready(orderId);
    }

    @PostMapping("/orders/{id}/pickup")
    public OrderSnapshot pickup(@PathVariable("id") String orderId) {
        return orderService.pickup(orderId);
    }

    @PostMapping("/orders/{id}/deliver")
    public OrderSnapshot deliver(@PathVariable("id") String orderId) {
        return orderService.deliver(orderId);
    }

    @PostMapping("/orders/{id}/cancel")
    public OrderSnapshot cancel(
            @PathVariable("id") String orderId, @RequestBody(required = false) @Nullable CancelRequest request) {
        CancelInitiator initiatedBy =
                request == null || request.initiatedBy() == null ? CancelInitiator.CUSTOMER : request.initiatedBy();
        return orderService.cancel(orderId, initiatedBy);
    }

    @PostMapping("/orders/{id}/reinstate")
    public OrderSnapshot reinstate(@PathVariable("id") String orderId) {
        return orderService.reinstate(orderId);
    }

    @GetMapping("/orders/{id}")
    public OrderSnapshot get(@PathVariable("id") String orderId) {
        return orderService.get(orderId);
    }

    @GetMapping("/outlets/{outletId}/orders")
    public List<OrderSnapshot> listForOutlet(
            @PathVariable String outletId, @RequestParam(name = "active", defaultValue = "false") boolean active) {
        return orderService.listForOutlet(outletId, active);
    }
}
