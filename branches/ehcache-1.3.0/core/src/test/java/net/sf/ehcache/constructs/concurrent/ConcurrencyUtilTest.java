/**
 *  Copyright 2003-2007 Greg Luck
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

package net.sf.ehcache.constructs.concurrent;

import junit.framework.TestCase;
import net.sf.ehcache.CacheException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Isolation tests for ConcurrencyUtil
 *
 * @author Greg Luck
 * @version $Id$
 */
public class ConcurrencyUtilTest extends TestCase {

    private static final Log LOG = LogFactory.getLog(ConcurrencyUtilTest.class.getName());


    /**
     * Tests that stripes are evently distributed
     */
    public void testStripingDistribution() {

        int[] lockIndexes = new int[2048];
        for (int i = 0; i < 20480 * 3; i++) {
            String key = "" + i * 3 / 2 + i;
            key += key.hashCode();
            int lock = ConcurrencyUtil.selectLock(key, 2048);
            lockIndexes[lock]++;
        }

        int outliers = 0;
        for (int i = 0; i < 2048; i++) {
            if (20 <= lockIndexes[i] && lockIndexes[i] <= 40) {
                continue;
            }
            LOG.info(i + ": " + lockIndexes[i]);
            outliers++;
        }
        assertTrue(outliers <= 128);
    }

    /**
     * Tests edge conditions for striping mechanism.
     */
    public void testNullKey() {
        ConcurrencyUtil.selectLock(null, 2048);
        ConcurrencyUtil.selectLock("", 2048);
    }


    /**
     * Tests edge conditions for striping mechanism.
     */
    public void testEvenLockNumber() {
        try {
            ConcurrencyUtil.selectLock("anything", 100);
        } catch (CacheException e) {
            //expected
        }
    }


}
