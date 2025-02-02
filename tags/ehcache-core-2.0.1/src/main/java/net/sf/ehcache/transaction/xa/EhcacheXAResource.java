/**
 *  Copyright 2003-2009 Terracotta, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package net.sf.ehcache.transaction.xa;

import javax.transaction.RollbackException;
import javax.transaction.SystemException;
import javax.transaction.xa.XAResource;

import net.sf.ehcache.transaction.TransactionContext;

/**
 *
 * EhcacheXAResource represents an {@link net.sf.ehcache.Ehcache Ehcache} instance.
 * It will provide the interface between the {@link javax.transaction.TransactionManager TransactionManager}
 * and the {@link net.sf.ehcache.store.XATransactionalStore XATransactionalStore} instance backing the transactional cache.
 *
 * @author Nabib El-Rahman
 * @author Alex Snaps
 */
public interface EhcacheXAResource extends XAResource {

    /**
     * Add a listener which will be called back according to the 2PC lifecycle
     * @param listener the TwoPcExecutionListener
     */
    void addTwoPcExecutionListener(TwoPcExecutionListener listener);

    /**
     * Getter to the name of the cache wrapped by this XAResource
     * @return {@link net.sf.ehcache.Ehcache#getName} value
     */
    String getCacheName();

    /**
     * Obtain the already associated {@link net.sf.ehcache.transaction.TransactionContext} with the current Transaction,
     * or create a new one should none be there yet.
     * @return The associated Transaction associated {@link net.sf.ehcache.transaction.TransactionContext} 
     * @throws SystemException Thrown if the associated transaction manager encounters an unexpected error condition.
     * @throws RollbackException Thrown if the resource has to be enlisted with the transaction, while it is marked for rollback only.
     */
    TransactionContext createTransactionContext() throws SystemException, RollbackException;

}