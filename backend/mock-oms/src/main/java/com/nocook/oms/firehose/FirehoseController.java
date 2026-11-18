package com.nocook.oms.firehose;

import com.nocook.oms.firehose.FirehoseService.Status;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/oms/api/firehose")
public class FirehoseController {

    public record StartRequest(@Min(1) @Max(200_000) long eventsPerSecond) {}

    public record StartResponse(boolean running, long eventsPerSecond) {}

    public record StopResponse(boolean running, long published) {}

    public record StatusResponse(boolean running, long eventsPerSecond, long achievedPerSecond, long published) {}

    private final FirehoseService service;

    public FirehoseController(FirehoseService service) {
        this.service = service;
    }

    @PostMapping
    public StartResponse start(@Valid @RequestBody StartRequest request) {
        Status status = service.start(request.eventsPerSecond());
        return new StartResponse(status.running(), status.eventsPerSecond());
    }

    @DeleteMapping
    public StopResponse stop() {
        Status status = service.stop();
        return new StopResponse(false, status.published());
    }

    @GetMapping
    public StatusResponse status() {
        Status status = service.status();
        return new StatusResponse(
                status.running(), status.eventsPerSecond(), Math.round(status.achievedPerSecond()), status.published());
    }
}
