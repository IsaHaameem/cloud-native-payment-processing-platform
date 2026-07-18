package com.paymentflow.merchant.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** {@code webhookUrl} may be {@code null}/blank to clear a previously configured webhook. */
public record UpdateWebhookRequest(
        @Size(max = 2048) @Pattern(regexp = "^https://.+", message = "webhookUrl must be an https:// URL") String webhookUrl) {
}
