package net.sf.ehcache.transaction.local;

import net.sf.ehcache.Element;
import net.sf.ehcache.transaction.TransactionID;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * @author lorban
 */
public class ReadCommittedSoftLockImpl implements SoftLock {
    private final ReadCommittedSoftLockFactoryImpl factory;
    private final TransactionID transactionID;
    private final Object key;
    private Element newElement;
    private final Element oldElement;
    private final ReentrantLock lock;
    private final ReentrantLock freezeLock;

    ReadCommittedSoftLockImpl(ReadCommittedSoftLockFactoryImpl factory, TransactionID transactionID, Object key, Element newElement, Element oldElement) {
        this.factory = factory;
        this.transactionID = transactionID;
        this.key = key;
        this.newElement = newElement;
        this.oldElement = oldElement;
        this.lock = new ReentrantLock();
        this.freezeLock = new ReentrantLock();
    }

    public Object getKey() {
        return key;
    }

    public Element getElement(TransactionID currentTransactionId) {
        freezeLock.lock();
        try {
            if (transactionID.equals(currentTransactionId)) {
                return newElement;
            } else {
                return oldElement;
            }
        } finally {
            freezeLock.unlock();
        }
    }

    public Element updateElement(Element newElement) {
        Element e = this.newElement;
        this.newElement = newElement;
        return e;
    }

    public TransactionID getTransactionID() {
        return transactionID;
    }

    public void lock() {
        lock.lock();
    }

    public boolean tryLock(long ms) throws InterruptedException {
        return lock.tryLock(ms, TimeUnit.MILLISECONDS);
    }

    public void unlock() {
        lock.unlock();
        factory.clearSoftLock(this);
    }

    public boolean isLocked() {
        return lock.isLocked();
    }

    public void freeze() {
        if (!isLocked()) {
            throw new IllegalStateException("cannot freeze an unlocked soft lock");
        }
        freezeLock.lock();
    }

    public Element getFrozenElement() {
        if (!isFrozen() && !isExpired()) {
            throw new IllegalStateException("cannot get frozen element of a soft lock which hasn't been frozen or hasn't expired");
        }
        if (transactionID.isDecisionCommit()) {
            return newElement;
        } else {
            return oldElement;
        }
    }

    public void unfreeze() {
        freezeLock.unlock();
    }

    private boolean isFrozen() {
        return freezeLock.isLocked();
    }

    public boolean isExpired() {
        return !isFrozen() && !isLocked();
    }

    @Override
    public boolean equals(Object object) {
        if (object instanceof ReadCommittedSoftLockImpl) {
            ReadCommittedSoftLockImpl other = (ReadCommittedSoftLockImpl) object;

            if (!transactionID.equals(other.transactionID)) {
                return false;
            }

            if (!key.equals(other.key)) {
                return false;
            }

            return true;
        }
        return false;
    }

    @Override
    public int hashCode() {
        int hashCode = 31;

        hashCode *= transactionID.hashCode();
        hashCode *= key.hashCode();

        return hashCode;
    }

    @Override
    public String toString() {
        return "Soft Lock [clustered: false, isolation: rc, transactionID: " + transactionID + ", key: " + key + ", newElement: " + newElement + ", oldElement: " + oldElement + "]";
    }
}
