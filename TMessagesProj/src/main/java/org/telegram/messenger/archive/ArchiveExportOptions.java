package org.telegram.messenger.archive;

/** Typed export scope. The all-accounts form preserves every stored account identity. */
public final class ArchiveExportOptions {
    public enum Scope { ALL_ACCOUNTS, ACCOUNT }

    public final Scope scope;
    public final int accountEnvironment;
    public final long accountId;
    public final boolean includeRawPayload;
    public final boolean includeMedia;

    private ArchiveExportOptions(Scope scope, int accountEnvironment, long accountId,
                                 boolean includeRawPayload, boolean includeMedia) {
        this.scope = scope;
        this.accountEnvironment = accountEnvironment;
        this.accountId = accountId;
        this.includeRawPayload = includeRawPayload;
        this.includeMedia = includeMedia;
    }

    public static ArchiveExportOptions allAccounts(boolean includeRawPayload) {
        return allAccounts(includeRawPayload, false);
    }

    public static ArchiveExportOptions allAccounts(boolean includeRawPayload, boolean includeMedia) {
        return new ArchiveExportOptions(Scope.ALL_ACCOUNTS, -1, 0, includeRawPayload, includeMedia);
    }

    public static ArchiveExportOptions account(int environment, long accountId,
                                               boolean includeRawPayload) {
        return account(environment, accountId, includeRawPayload, false);
    }

    public static ArchiveExportOptions account(int environment, long accountId,
                                               boolean includeRawPayload, boolean includeMedia) {
        if (accountId == 0) throw new IllegalArgumentException("accountId must be stable and non-zero");
        return new ArchiveExportOptions(Scope.ACCOUNT, environment, accountId, includeRawPayload, includeMedia);
    }
}
