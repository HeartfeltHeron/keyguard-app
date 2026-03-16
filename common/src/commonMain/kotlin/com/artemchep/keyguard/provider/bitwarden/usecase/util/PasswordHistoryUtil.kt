package com.artemchep.keyguard.provider.bitwarden.usecase.util

import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import kotlin.time.Instant

internal fun List<BitwardenCipher.Login.PasswordHistory>.withPasswordChange(
    previousPassword: String?,
    nextPassword: String?,
    at: Instant,
): List<BitwardenCipher.Login.PasswordHistory> {
    if (previousPassword == nextPassword || previousPassword == null) {
        return this
    }

    return this + BitwardenCipher.Login.PasswordHistory(
        password = previousPassword,
        lastUsedDate = at,
    )
}
