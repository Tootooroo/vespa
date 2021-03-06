// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.documentapi.messagebus.protocol;

import com.yahoo.jrt.slobrok.api.Mirror;
import com.yahoo.messagebus.metrics.CountMetric;
import com.yahoo.messagebus.metrics.MetricSet;
import com.yahoo.messagebus.metrics.ValueMetric;

import java.util.ArrayList;
import java.util.List;

/**
 * Load balances over a set of nodes based on statistics gathered from those nodes.
 *
 * @author thomasg
 */
public class LoadBalancer {

    public static class NodeMetrics extends MetricSet {
        public CountMetric sent = new CountMetric("sent", this);
        public CountMetric busy = new CountMetric("busy", this);
        public ValueMetric<Double> weight = new ValueMetric<Double>("weight", 1.0, this);

        public NodeMetrics(String name, MetricSet owner) {
            super(name);
            owner.addMetric(this);
        }
    }

    public static class Metrics extends MetricSet {
        MetricSet targets = new MetricSet("nodes");

        public Metrics(String name) {
            super(name);
            addMetric(targets);
        }
    }

    public static class Node {
        public Node(Mirror.Entry e, NodeMetrics m) { entry = e; metrics = m; }

        public Mirror.Entry entry;
        public NodeMetrics metrics;
    }

    /** Statistics on each node we are load balancing over. Populated lazily. */
    private List<NodeMetrics> nodeWeights = new ArrayList<NodeMetrics>();

    private Metrics metrics;
    private String cluster;
    private double position = 0.0;

    public LoadBalancer(String cluster, String session, Metrics metrics) {
        this.metrics = metrics;
        this.cluster = cluster;
    }

    public List<NodeMetrics> getNodeWeights() {
        return nodeWeights;
    }

    /** Returns the index from a node name string */
    public int getIndex(String nodeName) {
        try {
            String s = nodeName.substring(cluster.length() + 1);
            s = s.substring(0, s.indexOf("/"));
            s = s.substring(s.lastIndexOf(".") + 1);
            return Integer.parseInt(s);
        } catch (IndexOutOfBoundsException | NumberFormatException e) {
            String err = "Expected recipient on the form '" + cluster + "/x/[y.]number/z', got '" + nodeName + "'.";
            throw new IllegalArgumentException(err, e);
        }
    }

    /**
     * The load balancing operation: Returns a node choice from the given choices,
     * based on previously gathered statistics on the nodes, and a running "position"
     * which is increased by 1 on each call to this.
     *
     * @param choices the node choices, represented as Slobrok entries
     * @return the chosen node, or null only if the given choices were zero
     */
    public Node getRecipient(Mirror.Entry[] choices) {
        if (choices.length == 0) return null;

        double weightSum = 0.0;
        Node selectedNode = null;
        for (Mirror.Entry entry : choices) {
            NodeMetrics nodeMetrics = getNodeMetrics(entry);

            weightSum += nodeMetrics.weight.get();

            if (weightSum > position) {
                selectedNode = new Node(entry, nodeMetrics);
                break;
            }
        }
        if (selectedNode == null) { // Position>sum of all weights: Wrap around (but keep the remainder for some reason)
            position -= weightSum;
            selectedNode = new Node(choices[0], getNodeMetrics(choices[0]));
        }
        position += 1.0;
        selectedNode.metrics.sent.inc(1);
        return selectedNode;
    }

    /**
     * Returns the node metrics at a given index.
     * If there is no entry at the given index it is created by this call.
     */
    private NodeMetrics getNodeMetrics(Mirror.Entry entry) {
        int index = getIndex(entry.getName());
        // expand node array as needed
        while (nodeWeights.size() < (index + 1))
            nodeWeights.add(null);

        NodeMetrics nodeMetrics = nodeWeights.get(index);
        if (nodeMetrics == null) { // initialize statistics for this node
            nodeMetrics = new NodeMetrics("node_" + index, metrics.targets);
            nodeWeights.set(index, nodeMetrics);
        }
        return nodeMetrics;
    }

    /** Scale weights such that ratios are preserved */
    private void increaseWeights() {
        for (NodeMetrics n : nodeWeights) {
            if (n == null) continue;
            double want = n.weight.get() * 1.01010101010101010101;
            if (want >= 1.0) {
                n.weight.set(want);
            } else {
                n.weight.set(1.0);
            }
        }
    }

    public void received(Node node, boolean busy) {
        if (busy) {
            double wantWeight = node.metrics.weight.get() - 0.01;
            if (wantWeight < 1.0) {
                increaseWeights();
                node.metrics.weight.set(1.0);
            } else {
                node.metrics.weight.set(wantWeight);
            }
            node.metrics.busy.inc(1);
        }
    }

}
