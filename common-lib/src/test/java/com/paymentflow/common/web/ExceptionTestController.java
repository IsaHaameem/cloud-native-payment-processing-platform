package com.paymentflow.common.web;

import com.paymentflow.common.exception.ConflictException;
import com.paymentflow.common.exception.ResourceNotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Test-only controller that exercises each branch of {@link GlobalExceptionHandler}. */
@RestController
@RequestMapping("/test")
class ExceptionTestController {

    @GetMapping("/not-found")
    String notFound() {
        throw ResourceNotFoundException.of("Payment", "p-1");
    }

    @GetMapping("/conflict")
    String conflict() {
        throw new ConflictException("Already captured.");
    }

    @GetMapping("/boom")
    String boom() {
        throw new IllegalStateException("internal detail that must not leak");
    }

    @PostMapping("/validate")
    String validate(@Valid @RequestBody Payload payload) {
        return payload.name();
    }

    record Payload(@NotBlank(message = "name must not be blank") String name) {
    }
}
