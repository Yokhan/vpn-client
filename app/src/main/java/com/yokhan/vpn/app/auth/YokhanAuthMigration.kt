package com.yokhan.gates.app.auth

internal fun authIssuerMatches(storedIssuer: String?, expectedIssuer: String): Boolean =
    !storedIssuer.isNullOrBlank() &&
        storedIssuer.trimEnd('/') == expectedIssuer.trimEnd('/')
