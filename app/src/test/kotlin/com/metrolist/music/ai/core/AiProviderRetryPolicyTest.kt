package com.metrolist.music.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiProviderRetryPolicyTest {
    @Test
    fun `retries only transient failures before output`() {
        assertEquals(1L, AiError(AiErrorType.NETWORK, "offline").retryDelaySeconds(0, false))
        assertEquals(2L, AiError(AiErrorType.PROVIDER_SERVER, "503").retryDelaySeconds(1, false))
        assertNull(AiError(AiErrorType.INVALID_API_KEY, "401").retryDelaySeconds(0, false))
        assertNull(AiError(AiErrorType.NETWORK, "late").retryDelaySeconds(0, true))
        assertNull(AiError(AiErrorType.NETWORK, "loop").retryDelaySeconds(2, false))
    }

    @Test
    fun `rate limit retry respects bounded Retry-After`() {
        assertEquals(12L, AiError(AiErrorType.RATE_LIMITED, "429", retryAfterSeconds = 12).retryDelaySeconds(0, false))
        assertNull(AiError(AiErrorType.RATE_LIMITED, "429", retryAfterSeconds = null).retryDelaySeconds(0, false))
        assertNull(AiError(AiErrorType.RATE_LIMITED, "429", retryAfterSeconds = 120).retryDelaySeconds(0, false))
    }
}
