/**
 *  Copyright 2003-2010 Terracotta, Inc.
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
package net.sf.ehcache.transaction.local;

import net.sf.ehcache.Element;

/**
 * A soft lock is used to lock elements in transactional stores
 * 
 * @author Ludovic Orban
 */
public interface SoftLock {

    /**
     * Get the key of the element this soft lock is guarding
     * @return the key
     */
    Object getKey();

    /**
     * Get the ID of the transaction under which this soft lock is operating
     * @return the TransactionID
     */
    TransactionID getTransactionID();

    /**
     * Get the element the current transaction is supposed to see.
     * @param currentTransactionId the current transaction under which this call is executed
     * @return the Element visible to the current transaction
     */
    Element getElement(TransactionID currentTransactionId);

    /**
     * Change the Element at the key this soft lock is guarding
     * @param element the new Element
     * @return the previous Element
     */
    Element updateElement(Element element);

    /**
     * Lock the soft lock
     */
    void lock();

    /**
     * Attempt to lock the soft lock
     * @param ms the time in milliseconds before this method gives up
     * @return true if the soft lock was locked, false otherwise
     * @throws InterruptedException if the thread calling this method was interrupted
     */
    boolean tryLock(long ms) throws InterruptedException;

    /**
     * Unlock the soft lock. Once a soft lock got unlocked, it is considered 'dead': it cannot be
     * locked again and must be cleaned up
     */
    void unlock();

    /**
     * Freeze the soft lock. A soft lock should only be frozen for a very short period of time as this blocks
     * the {@link #getElement(TransactionID)} method calls.
     * Freeze is used to mark the start of a commit / rollback phase
     */
    void freeze();

    /**
     * Unfreeze the soft lock
     */
    void unfreeze();

    /**
     * Check if the soft lock expired, ie: that the thread which locked it died
     * @return true if the soft lock is orphan and should be cleaned up, false otherwise
     */
    boolean isExpired();

    /**
     * Get the Element with which this soft lock should be replaced by a commit, rollback or clean up
     * @return the Element
     */
    Element getFrozenElement();

}
