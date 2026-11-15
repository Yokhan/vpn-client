package com.yokhan.gates.app.auth

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

internal fun authIssuerMatches(storedIssuer: String?, expectedIssuer: String): Boolean =
    !storedIssuer.isNullOrBlank() &&
        storedIssuer.trimEnd('/') == expectedIssuer.trimEnd('/')

internal fun isAuthNetworkTimeoutOrUnreachable(error: Throwable?): Boolean {
    var current: Throwable? = error
    var depth = 0
    while (current != null && depth < 10) {
        if (current is SocketTimeoutException ||
            current is UnknownHostException ||
            current is ConnectException ||
            current is NoRouteToHostException
        ) {
            return true
        }
        current = current.cause
        depth++
    }
    return false
}
