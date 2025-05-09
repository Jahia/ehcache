package net.sf.ehcache.transaction.local;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author lorban
 */
public class TransactionContext {

    private static final Logger LOG = LoggerFactory.getLogger(TransactionContext.class.getName());

    private boolean rollbackOnly;
    private final long expirationTimestamp;
    private final TransactionID transactionId;
    private final Map<String, List<SoftLock>> softLockMap = new HashMap<String, List<SoftLock>>();
    private final Map<String, LocalTransactionStore> storeMap = new HashMap<String, LocalTransactionStore>();
    private final List<TransactionListener> listeners = new ArrayList<TransactionListener>();

    public TransactionContext(int transactionTimeout, TransactionID transactionId) {
        this.expirationTimestamp = System.currentTimeMillis() + transactionTimeout * 1000L;
        this.transactionId = transactionId;
    }

    public long getExpirationTimestamp() {
        return expirationTimestamp;
    }

    public boolean timedOut() {
        return expirationTimestamp <= System.currentTimeMillis();
    }

    public void setRollbackOnly(boolean rollbackOnly) {
        this.rollbackOnly = rollbackOnly;
    }

    public void registerSoftLock(String cacheName, LocalTransactionStore store, SoftLock softLock) {
        List<SoftLock> softLocks = softLockMap.get(cacheName);
        if (softLocks == null) {
            softLocks = new ArrayList<SoftLock>();
            softLockMap.put(cacheName, softLocks);
            storeMap.put(cacheName, store);
        }
        softLocks.add(softLock);
    }

    //todo this method isn't needed if there is no copy on read/write in the underlying store
    public void updateSoftLock(String cacheName, SoftLock softLock) {
        List<SoftLock> softLocks = softLockMap.get(cacheName);
        softLocks.remove(softLock);
        softLocks.add(softLock);
    }


    public List<SoftLock> getSoftLocks(String cacheName) {
        List<SoftLock> softLocks = softLockMap.get(cacheName);
        if (softLocks == null) {
            return Collections.emptyList();
        }

        return Collections.unmodifiableList(softLocks);
    }

    public void commit(boolean ignoreTimeout) {
        if (!ignoreTimeout && timedOut()) {
            rollback();
            throw new TransactionTimeoutException("transaction timed out, rolled back on commit");
        }
        if (rollbackOnly) {
            rollback();
            throw new TransactionException("transaction was marked as rollback only, rolled back on commit");
        }

        try {
            fireBeforeCommitEvent();
            if (LOG.isDebugEnabled()) {
                LOG.debug("{} participating cache(s), committing transaction {}", softLockMap.keySet().size(), transactionId);
            }
            freeze();
            transactionId.markForCommit();

            for (Map.Entry<String, List<SoftLock>> stringListEntry : softLockMap.entrySet()) {
                String cacheName = stringListEntry.getKey();
                LocalTransactionStore store = storeMap.get(cacheName);
                List<SoftLock> softLocks = stringListEntry.getValue();

                LOG.debug("committing soft locked values of cache {}", cacheName);
                store.commit(softLocks);
            }
            LOG.debug("committed transaction {}", transactionId);
        } finally {
            unfreezeAndUnlock();
            softLockMap.clear();
            storeMap.clear();
            fireAfterCommitEvent();
        }
    }

    public void rollback() {
        try {
            if (LOG.isDebugEnabled()) {
                LOG.debug("{} participating cache(s), rolling back transaction {}", softLockMap.keySet().size(), transactionId);
            }
            freeze();

            for (Map.Entry<String, List<SoftLock>> stringListEntry : softLockMap.entrySet()) {
                String cacheName = stringListEntry.getKey();
                LocalTransactionStore store = storeMap.get(cacheName);
                List<SoftLock> softLocks = stringListEntry.getValue();

                LOG.debug("rolling back soft locked values of cache {}", cacheName);
                store.rollback(softLocks);
            }
            LOG.debug("rolled back transaction {}", transactionId);
        } finally {
            unfreezeAndUnlock();
            softLockMap.clear();
            storeMap.clear();
            fireAfterRollbackEvent();
        }
    }

    public TransactionID getTransactionId() {
        return transactionId;
    }

    public void addListener(TransactionListener listener) {
        this.listeners.add(listener);
    }

    private void fireBeforeCommitEvent() {
        for (TransactionListener listener : listeners) {
            listener.beforeCommit();
        }
    }

    private void fireAfterCommitEvent() {
        for (TransactionListener listener : listeners) {
            listener.afterCommit();
        }
    }

    private void fireAfterRollbackEvent() {
        for (TransactionListener listener : listeners) {
            listener.afterRollback();
        }
    }

    private void unfreezeAndUnlock() {
        for (Map.Entry<String, List<SoftLock>> stringListEntry : softLockMap.entrySet()) {
            List<SoftLock> softLocks = stringListEntry.getValue();

            for (SoftLock softLock : softLocks) {
                try {
                    softLock.unfreeze();
                } catch (Exception e) {
                    LOG.error("error unfreezing " + softLock, e);
                }
                try {
                    softLock.unlock();
                } catch (Exception e) {
                    LOG.error("error unlocking " + softLock, e);
                }
            }
        }
    }

    private void freeze() {
        for (Map.Entry<String, List<SoftLock>> stringListEntry : softLockMap.entrySet()) {
            List<SoftLock> softLocks = stringListEntry.getValue();

            for (SoftLock softLock : softLocks) {
                softLock.freeze();
            }
        }
    }

    @Override
    public int hashCode() {
        return transactionId.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof TransactionContext) {
            TransactionContext otherCtx = (TransactionContext) obj;
            return transactionId.equals(otherCtx.transactionId);
        }
        return false;
    }

}
