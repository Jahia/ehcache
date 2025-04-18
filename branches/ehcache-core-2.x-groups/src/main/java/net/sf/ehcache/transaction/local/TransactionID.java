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

/**
 * A transaction identifier. Transaction ID's must be unique to the entire
 * cluster-wide CacheManager.
 *
 * @author Ludovic Orban
 */
public interface TransactionID {

    /**
     * Check if this transaction should be committed or not
     * @return true of the transaction should be committed
     */
    boolean isDecisionCommit();

    /**
     * Mark that this transaction's decision is commit
     */
    void markForCommit();

}
