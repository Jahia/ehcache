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

package net.sf.ehcache.terracotta;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import junit.framework.Assert;
import junit.framework.TestCase;
import net.sf.ehcache.CacheManager;
import net.sf.ehcache.cluster.CacheCluster;
import net.sf.ehcache.config.InvalidConfigurationException;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.runners.MockitoJUnitRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RunWith(MockitoJUnitRunner.class)
public class StorageStrategyInvalidConfigurationTest extends TestCase {

    private static final Logger LOG = LoggerFactory.getLogger(StorageStrategyInvalidConfigurationTest.class);

    @Test
    public void testInvalidRejoinWithoutNonstop() throws Exception {
        ClusteredInstanceFactory mockFactory = mock(ClusteredInstanceFactory.class);
        TerracottaUnitTesting.setupTerracottaTesting(mockFactory);

        CacheCluster mockCacheCluster = new MockCacheCluster();
        when(mockFactory.getTopology()).thenReturn(mockCacheCluster);

        try {
            new CacheManager(CacheManager.class.getResourceAsStream("/ehcache-classic-with-nonstop-invalid-configuration-test.xml"));
            fail("Trying to run rejoin without nonstop terracotta caches should fail");
        } catch (InvalidConfigurationException e) {
            LOG.info("Caught expected exception: " + e);
            String emsg = e.getMessage();
            Assert.assertTrue(emsg.indexOf("NONSTOP can't be enabled with CLASSIC strategy. Invalid Cache: cache2") > 0);
            Assert.assertTrue(emsg.indexOf("REJOIN can't be enabled with CLASSIC strategy. Invalid Cache: cache2") > 0);
            Assert.assertTrue(emsg.indexOf("EVENTUAL consistency can't be enabled with CLASSIC strategy. Invalid Cache: cache2") > 0);
            Assert.assertTrue(emsg.indexOf("NONSTOP can't be enabled with CLASSIC strategy. Invalid Cache: cache1") > 0);
            Assert.assertTrue(emsg.indexOf("REJOIN can't be enabled with CLASSIC strategy. Invalid Cache: cache1") > 0);
            Assert.assertTrue(emsg.indexOf("EVENTUAL consistency can't be enabled with CLASSIC strategy. Invalid Cache: cache1") > 0);
            Assert.assertTrue(emsg.indexOf("REJOIN can't be enabled with CLASSIC strategy. Invalid Cache: cache3") > 0);
            Assert.assertTrue(emsg.indexOf("EVENTUAL consistency can't be enabled with CLASSIC strategy. Invalid Cache: cache3") > 0);
            Assert.assertTrue(emsg.indexOf("Terracotta clustered caches must be nonstop when rejoin is enabled. Invalid cache: cache3") > 0);
        }
    }
}
