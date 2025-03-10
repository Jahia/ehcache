package net.sf.ehcache.transaction.local;

import net.sf.ehcache.TransactionController;
import net.sf.ehcache.transaction.TransactionID;

import javax.transaction.*;

/**
 * @author Ludovic Orban
 */
public class JtaLocalEhcacheSynchronization implements Synchronization {

    private TransactionController transactionController;

    public JtaLocalEhcacheSynchronization(TransactionController transactionController) {
        this.transactionController = transactionController;
    }

    public void beforeCompletion() {
        //
    }

    public void afterCompletion(int status) {
        //TODO this method may not be executed from the thread which started the TX
        // but calling TransactionContext.commit() won't clean up the TransactionController
        if (status == javax.transaction.Status.STATUS_COMMITTED) {
            transactionController.commit(true);
        } else {
            transactionController.rollback();
        }
    }

    @Override
    public String toString() {
        TransactionContext currentTransactionContext = transactionController.getCurrentTransactionContext();
        TransactionID transactionId = currentTransactionContext == null ? null : currentTransactionContext.getTransactionId();
        return "JtaLocalEhcacheSynchronization of transaction [" + transactionId + "]";
    }
}
