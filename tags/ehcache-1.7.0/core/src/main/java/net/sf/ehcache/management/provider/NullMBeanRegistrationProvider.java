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
package net.sf.ehcache.management.provider;

import net.sf.ehcache.CacheManager;

/**
 * A Null implementation of {@link MBeanRegistrationProvider} which does nothing
 * <p />
 * 
 * @author <a href="mailto:asanoujam@terracottatech.com">Abhishek Sanoujam</a>
 * @since 1.7
 */
public class NullMBeanRegistrationProvider implements MBeanRegistrationProvider {

    /**
     * A null implementation of
     * {@link MBeanRegistrationProvider#initialize(CacheManager)}
     */
    public void initialize(CacheManager cacheManager) {
        // no-op
    }

    /**
     * A null implementation of
     * {@link MBeanRegistrationProvider#reinitialize()}
     */
    public void reinitialize()
            throws MBeanRegistrationProviderException {
        // no-op

    }

}
