// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.provision.provisioning;

import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.ClusterMembership;
import com.yahoo.config.provision.ClusterSpec;
import com.yahoo.lang.MutableInteger;
import com.yahoo.vespa.hosted.provision.Node;
import com.yahoo.vespa.hosted.provision.NodeRepository;
import com.yahoo.vespa.hosted.provision.node.Flavor;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Optional;

/**
 * Performs preparation of node activation changes for an application.
 *
 * @author bratseth
 */
class Preparer {

    private final NodeRepository nodeRepository;
    private final Clock clock;
    private final GroupPreparer groupPreparer;

    public Preparer(NodeRepository nodeRepository, Clock clock) {
        this.nodeRepository = nodeRepository;
        this.clock = clock;
        groupPreparer = new GroupPreparer(nodeRepository, clock);
    }

    /**
     * Ensure sufficient nodes are reserved or active for the given application and cluster
     *
     * @return the list of nodes this cluster will have allocated if activated
     */
     // Note: This operation may make persisted changes to the set of reserved and inactive nodes,
     // but it may not change the set of active nodes, as the active nodes must stay in sync with the
     // active config model which is changed on activate
    public List<Node> prepare(ApplicationId application, ClusterSpec cluster, int nodes, Flavor flavor, int wantedGroups) {
        // TODO: Encode actual assumptions as we have logic that depends on them:
        // - Don't allow a cluster spec specifying an explicit group (and then remove the "targetgroup" parameter to moveToActiveGroup
        // - Change group ids to be a 0-based integer index
        if (cluster.group().isPresent() && wantedGroups > 1)
            throw new IllegalArgumentException("Cannot specify both a particular group and request multiple groups");
        if (nodes > 0 && nodes % wantedGroups != 0)
            throw new IllegalArgumentException("Requested " + nodes + " nodes in " + wantedGroups + " groups, " +
                                               "which doesn't allow the nodes to be divided evenly into groups");

        // no group -> this asks for the entire cluster -> we are free to remove groups we won't need
        List<Node> surplusNodes =
            cluster.group().isPresent() ? new ArrayList<>() : findNodesInRemovableGroups(application, cluster, wantedGroups);

        MutableInteger highestIndex = new MutableInteger(findHighestIndex(application, cluster));
        List<Node> acceptedNodes = new ArrayList<>();
        for (int groupIndex = 0; groupIndex < wantedGroups; groupIndex++) {
            // Generated groups always have contiguous indexes starting from 0
            ClusterSpec clusterGroup =
                cluster.group().isPresent() ? cluster : cluster.changeGroup(Optional.of(ClusterSpec.Group.from(String.valueOf(groupIndex))));

            List<Node> accepted = groupPreparer.prepare(application, clusterGroup, nodes/wantedGroups, flavor, surplusNodes, highestIndex);
            replace(acceptedNodes, accepted);
        }
        moveToActiveGroup(surplusNodes, wantedGroups, cluster.group());
        replace(acceptedNodes, retire(surplusNodes));
        return acceptedNodes;
    }

    /**
     * Returns a list of the nodes which are
     * in groups with index number above or equal the group count
     */
    private List<Node> findNodesInRemovableGroups(ApplicationId application, ClusterSpec requestedCluster, int wantedGroups) {
        List<Node> surplusActiveNodes = new ArrayList<>(0);
        for (Node node : nodeRepository.getNodes(application, Node.State.active)) {
            ClusterSpec nodeCluster = node.allocation().get().membership().cluster();
            if ( ! nodeCluster.id().equals(requestedCluster.id())) continue;
            if ( ! nodeCluster.type().equals(requestedCluster.type())) continue;
            if (Integer.parseInt(nodeCluster.group().get().value()) >= wantedGroups)
                surplusActiveNodes.add(node);
        }
        return surplusActiveNodes;
    }
    
    /** Move nodes from unwanted groups to wanted groups to avoid lingering groups consisting of retired nodes */
    private void moveToActiveGroup(List<Node> surplusNodes, int wantedGroups, Optional<ClusterSpec.Group> targetGroup) {
        for (ListIterator<Node> i = surplusNodes.listIterator(); i.hasNext(); ) {
            Node node = i.next();
            ClusterMembership membership = node.allocation().get().membership();
            ClusterSpec cluster = membership.cluster();
            if (Integer.parseInt(cluster.group().get().value()) >= wantedGroups) {
                ClusterSpec.Group newGroup = targetGroup.orElse(ClusterSpec.Group.from(String.valueOf(0)));
                ClusterMembership newGroupMembership = membership.changeCluster(cluster.changeGroup(Optional.of(newGroup)));
                i.set(node.setAllocation(node.allocation().get().changeMembership(newGroupMembership)));
            }
        }
    }
    
    private List<Node> replace(List<Node> list, List<Node> changed) {
        list.removeAll(changed);
        list.addAll(changed);
        return list;
    }

    /**
     * Returns the highest index number of all active and failed nodes in this cluster, or -1 if there are no nodes.
     * We include failed nodes to avoid reusing the index of the failed node in the case where the failed node is the
     * node with the highest index.
     */
    private int findHighestIndex(ApplicationId application, ClusterSpec cluster) {
        int highestIndex = -1;
        for (Node node : nodeRepository.getNodes(application, Node.State.active, Node.State.failed)) {
            ClusterSpec nodeCluster = node.allocation().get().membership().cluster();
            if ( ! nodeCluster.id().equals(cluster.id())) continue;
            if ( ! nodeCluster.type().equals(cluster.type())) continue;

            highestIndex = Math.max(node.allocation().get().membership().index(), highestIndex);
        }
        return highestIndex;
    }

    /** Returns retired copies of the given nodes, unless they are removable */
    private List<Node> retire(List<Node> nodes) {
        List<Node> retired = new ArrayList<>(nodes.size());
        for (Node node : nodes) {
            if ( ! node.allocation().get().removable())
                retired.add(node.retireByApplication(clock.instant()));
        }
        return retired;
    }

}
