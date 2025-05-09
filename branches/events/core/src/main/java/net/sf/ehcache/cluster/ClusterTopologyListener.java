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
package net.sf.ehcache.cluster;

/**
 * A listener for cluster topology events
 * @author Alex Miller
 * @since 1.8
 */
public interface ClusterTopologyListener {

    /**
     * A node has joined the cluster
     * @param node The joining node
     */
    void nodeJoined(ClusterNode node);
    
    /**
     * A node has left the cluster
     * @param node The departing node
     */
    void nodeLeft(ClusterNode node);

    /**
     * This node has established contact with the cluster and can execute clustered operations.
     * @param node The current node
     */
    void clusterOnline(ClusterNode node);
    
    /**
     * This node has lost contact (possibly temporarily) with the cluster and cannot execute 
     * clustered operations
     * @param node The current node
     */
    void clusterOffline(ClusterNode node);
    
}
