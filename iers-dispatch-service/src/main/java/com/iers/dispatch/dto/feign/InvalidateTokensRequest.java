package com.iers.dispatch.dto.feign;

import java.util.List;

/** Body of POST /internal/push-tokens/invalidate (Auth accepts 1 to 500 tokens). */
public record InvalidateTokensRequest(List<String> tokens) {
}
