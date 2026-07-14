package org.telegram.messenger.archive;

import java.util.Objects;

/** Stable Telegram account identity stored in an archive; never inferred from an account slot. */
public final class ArchiveAccountIdentity {
    public final int accountEnvironment;
    public final long accountId;

    public ArchiveAccountIdentity(int accountEnvironment, long accountId) {
        this.accountEnvironment = accountEnvironment;
        this.accountId = accountId;
    }

    @Override
    public boolean equals(Object value) {
        if (this == value) return true;
        if (!(value instanceof ArchiveAccountIdentity)) return false;
        ArchiveAccountIdentity other = (ArchiveAccountIdentity) value;
        return accountEnvironment == other.accountEnvironment && accountId == other.accountId;
    }

    @Override
    public int hashCode() {
        return Objects.hash(accountEnvironment, accountId);
    }
}
