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
package net.sf.ehcache.config;

import net.sf.ehcache.store.compound.CopyStrategy;

/**
 * @author Alex Snaps
 */
public class CopyStrategyConfiguration {

    private volatile String className = "net.sf.ehcache.store.compound.SerializationCopyStrategy";
    private CopyStrategy strategy;

    /**
     * Returns the fully qualified class name for the CopyStrategy to use
     * @return FQCN to the CopyStrategy implementation to use
     */
    public String getClassName() {
        return className;
    }

    /**
     * Sets the fully qualified class name for the CopyStrategy to use
     * @param className FQCN
     */
    public void setClass(final String className) {
        this.className = className;
    }

    /**
     * Get (and potentially) instantiate the instance
     * @return the instance
     */
    public synchronized CopyStrategy getCopyStrategyInstance() {
        if (strategy == null) {
            Class copyStrategy = null;
            try {
                copyStrategy = Class.forName(className);
                strategy = (CopyStrategy) copyStrategy.newInstance();
            } catch (ClassNotFoundException e) {
                throw new RuntimeException("Couldn't find the CopyStrategy class!", e);
            } catch (InstantiationException e) {
                throw new RuntimeException("Couldn't instantiate the CopyStrategy instance!", e);
            } catch (IllegalAccessException e) {
                throw new RuntimeException("Couldn't instantiate the CopyStrategy instance!", e);
            } catch (ClassCastException e) {
                throw new RuntimeException(copyStrategy != null ? copyStrategy.getSimpleName()
                                                                  + " doesn't implement net.sf.ehcache.store.compound.CopyStrategy"
                    : "Error with CopyStrategy", e);
            }
        }
        return strategy;
    }
}
