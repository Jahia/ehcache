/**
 *  Copyright Terracotta, Inc.
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

package net.sf.ehcache.statisticsV2.extended;

import java.util.concurrent.TimeUnit;

import net.sf.ehcache.statisticsV2.extended.ExtendedStatistics.Operation;


public interface FlatExtendedStatistics {

    void setStatisticsTimeToDisable(long time, TimeUnit unit);

    Operation cacheGetOperation();
    Operation cacheHitOperation();
    Operation cacheMissExpiredOperation();
    Operation cacheMissNotFoundOperation();
    Operation cacheMissOperation();
    Operation cachePutAddedOperation();
    Operation cachePutReplacedOperation();
    Operation cachePutOperation();
    Operation cacheRemoveOperation();

    Operation localHeapHitOperation();
    Operation localHeapMissOperation();
    Operation localHeapPutAddedOperation();
    Operation localHeapPutReplacedOperation();
    Operation localHeapPutOperation();
    Operation localHeapRemoveOperation();

    Operation localOffHeapHitOperation();
    Operation localOffHeapMissOperation();
    Operation localOffHeapPutAddedOperation();
    Operation localOffHeapPutReplacedOperation();
    Operation localOffHeapPutOperation();
    Operation localOffHeapRemoveOperation();

    Operation diskHeapHitOperation();
    Operation diskHeapMissOperation();
    Operation diskHeapPutAddedOperation();
    Operation diskHeapPutReplacedOperation();
    Operation diskHeapPutOperation();
    Operation diskHeapRemoveOperation();

    Operation cacheSearchOperation();

    Operation xaCommitSuccessOperation();
    Operation xaCommitExceptionOperation();
    Operation xaCommitReadOnlyOperation();
    Operation xaRollbackOperation();
    Operation xaRollbackExceptionOperation();
    Operation xaRecoveryOperation();

    Operation evictionOperation();
    Operation expiredOperation();

    // pass through stats
    long getLocalHeapSizeInBytes();

    long calculateInMemorySize();

    long getMemoryStoreSize();

    int getDiskStoreSize();

    long calculateOffHeapSize();

    long getOffHeapStoreSize();

    long getObjectCount();

    long getMemoryStoreObjectCount();

    long getDiskStoreObjectCount();

    long getLocalHeapSize();

    long getWriterQueueSize();

    long getOffHeapStoreObjectCount();

    String getLocalHeapSizeString();

    long getWriterQueueLength();

    long getLocalDiskSize();

    long getLocalOffHeapSize();

    long getLocalDiskSizeInBytes();

    long getLocalOffHeapSizeInBytes();

    long getSize();

}