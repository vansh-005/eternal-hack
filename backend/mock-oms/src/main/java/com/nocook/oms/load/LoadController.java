package com.nocook.oms.load;

import com.nocook.oms.orders.OrderModel.Stage;
import com.nocook.oms.orders.OrderService;
import com.nocook.oms.orders.OrderSnapshot;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/oms/api/load")
public class LoadController {

    public record AcceptedOrderRequest(
            @NotBlank String outletId,
            @Nullable Stage stage,
            @PositiveOrZero long acceptedSecondsAgo,
            @PositiveOrZero long valuePaise) {}

    private final OrderService orderService;

    public LoadController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping("/accepted-orders")
    @ResponseStatus(HttpStatus.CREATED)
    public OrderSnapshot createAccepted(
            @Valid @RequestBody AcceptedOrderRequest request,
            @RequestHeader(value = "X-Run-Id", required = false) @Nullable String runId) {
        Stage stage = request.stage() == null ? Stage.PREP : request.stage();
        return orderService.createAccepted(
                request.outletId(), stage, request.acceptedSecondsAgo(), request.valuePaise(), runId);
    }
}
