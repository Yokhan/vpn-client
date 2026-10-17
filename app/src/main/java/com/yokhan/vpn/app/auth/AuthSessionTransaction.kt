package com.yokhan.gates.app.auth

import com.yokhan.gates.app.backup.ProfileMutationGate
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal suspend fun <S, T> withStoredAuthSession(
    gate: ProfileMutationGate,
    load: () -> S,
    save: (S) -> Unit,
    action: suspend (S) -> T,
): T = gate.withLock {
    // Once refresh rotates a token, cancellation must not discard its successor.
    withContext(NonCancellable) {
        val state = load()
        try {
            action(state)
        } finally {
            save(state)
        }
    }
}
