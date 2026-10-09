package com.labteto.dshmobile.ui.screens.tools

import com.labteto.dshmobile.R
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalToolConnectionErrorTest {
    @Test fun meaningfulFailuresMapToActionableAndSafeMessages() {
        assertEquals(R.string.tools_error_dns, localToolConnectionErrorMessageRes(UnknownHostException("token-private")))
        assertEquals(R.string.tools_error_timeout, localToolConnectionErrorMessageRes(SocketTimeoutException("token-private")))
        assertEquals(R.string.tools_error_unreachable, localToolConnectionErrorMessageRes(ConnectException("token-private")))
        assertEquals(R.string.tools_error_permission, localToolConnectionErrorMessageRes(SecurityException("token-private")))
        assertEquals(R.string.tools_error_config, localToolConnectionErrorMessageRes(IllegalArgumentException("token-private")))
        assertEquals(R.string.tools_error_generic, localToolConnectionErrorMessageRes(IllegalStateException("token-private")))
    }

    @Test fun wrappedFailuresAreClassifiedByRootCause() {
        assertEquals(
            R.string.tools_error_timeout,
            localToolConnectionErrorMessageRes(IllegalStateException("wrapper", SocketTimeoutException("timeout"))),
        )
    }
}
