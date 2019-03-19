// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.clustercontroller.core;

import com.yahoo.vdslib.state.*;
import com.yahoo.vespa.clustercontroller.core.database.DatabaseHandler;
import com.yahoo.vespa.clustercontroller.core.listeners.NodeAddedOrRemovedListener;
import com.yahoo.vespa.clustercontroller.core.listeners.NodeStateOrHostInfoChangeHandler;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

public class SystemStateBroadcasterTest {

    private static class Fixture {
        FakeTimer timer = new FakeTimer();
        final Object monitor = new Object();
        SystemStateBroadcaster broadcaster = new SystemStateBroadcaster(timer, monitor);
        Communicator mockCommunicator = mock(Communicator.class);
        DatabaseHandler mockDatabaseHandler = mock(DatabaseHandler.class);
        FleetController mockFleetController = mock(FleetController.class);

        void simulateNodePartitionedAwaySilently(ClusterFixture cf) {
            cf.cluster().getNodeInfo(Node.ofStorage(0)).setStartTimestamp(600);
            cf.cluster().getNodeInfo(Node.ofStorage(1)).setStartTimestamp(700);
            // Simulate a distributor being partitioned away from the controller without actually going down. It will
            // need to observe all startup timestamps to infer if it should fetch bucket info from nodes.
            cf.cluster().getNodeInfo(Node.ofDistributor(0)).setStartTimestamp(500); // FIXME multiple sources of timestamps are... rather confusing
            cf.cluster().getNodeInfo(Node.ofDistributor(0)).setReportedState(new NodeState(NodeType.DISTRIBUTOR, State.UP).setStartTimestamp(500), 1000);
            cf.cluster().getNodeInfo(Node.ofDistributor(0)).setReportedState(new NodeState(NodeType.DISTRIBUTOR, State.DOWN).setStartTimestamp(500), 2000);
            cf.cluster().getNodeInfo(Node.ofDistributor(0)).setReportedState(new NodeState(NodeType.DISTRIBUTOR, State.UP).setStartTimestamp(500), 3000);
        }

        void simulateBroadcastTick(ClusterFixture cf) {
            broadcaster.processResponses();
            broadcaster.broadcastNewStateBundleIfRequired(dbContextFrom(cf.cluster()), mockCommunicator);
            try {
                broadcaster.checkIfClusterStateIsAckedByAllDistributors(
                        mockDatabaseHandler, dbContextFrom(cf.cluster()), mockFleetController);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            broadcaster.broadcastStateActivationsIfRequired(dbContextFrom(cf.cluster()), mockCommunicator); // nope!
        }
    }

    private static DatabaseHandler.Context dbContextFrom(ContentCluster cluster) {
        return new DatabaseHandler.Context() {
            @Override
            public ContentCluster getCluster() {
                return cluster;
            }

            @Override
            public FleetController getFleetController() {
                return null; // We assume the broadcaster doesn't use this for our test purposes
            }

            @Override
            public NodeAddedOrRemovedListener getNodeAddedOrRemovedListener() {
                return null;
            }

            @Override
            public NodeStateOrHostInfoChangeHandler getNodeStateUpdateListener() {
                return null;
            }
        };
    }

    private static Stream<NodeInfo> clusterNodeInfos(ContentCluster c, Node... nodes) {
        return Stream.of(nodes).map(c::getNodeInfo);
    }

    @Test
    public void always_publish_baseline_cluster_state() {
        Fixture f = new Fixture();
        ClusterStateBundle stateBundle = ClusterStateBundleUtil.makeBundle("distributor:2 storage:2");
        ClusterFixture cf = ClusterFixture.forFlatCluster(2).bringEntireClusterUp().assignDummyRpcAddresses();
        f.broadcaster.handleNewClusterStates(stateBundle);
        f.broadcaster.broadcastNewStateBundleIfRequired(dbContextFrom(cf.cluster()), f.mockCommunicator);
        cf.cluster().getNodeInfo().forEach(nodeInfo -> verify(f.mockCommunicator).setSystemState(eq(stateBundle), eq(nodeInfo), any()));
    }

    @Test
    public void non_observed_startup_timestamps_are_published_per_node_for_baseline_state() {
        Fixture f = new Fixture();
        ClusterStateBundle stateBundle = ClusterStateBundleUtil.makeBundle("distributor:2 storage:2");
        ClusterFixture cf = ClusterFixture.forFlatCluster(2).bringEntireClusterUp().assignDummyRpcAddresses();
        f.simulateNodePartitionedAwaySilently(cf);
        f.broadcaster.handleNewClusterStates(stateBundle);
        f.broadcaster.broadcastNewStateBundleIfRequired(dbContextFrom(cf.cluster()), f.mockCommunicator);

        clusterNodeInfos(cf.cluster(), Node.ofDistributor(1), Node.ofStorage(0), Node.ofStorage(1)).forEach(nodeInfo -> {
            // Only distributor 0 should observe startup timestamps
            verify(f.mockCommunicator).setSystemState(eq(stateBundle), eq(nodeInfo), any());
        });
        ClusterStateBundle expectedDistr0Bundle = ClusterStateBundleUtil.makeBundle("distributor:2 storage:2 .0.t:600 .1.t:700");
        verify(f.mockCommunicator).setSystemState(eq(expectedDistr0Bundle), eq(cf.cluster().getNodeInfo(Node.ofDistributor(0))), any());
    }

    @Test
    public void bucket_space_states_are_published_verbatim_when_no_additional_timestamps_needed() {
        Fixture f = new Fixture();
        ClusterStateBundle stateBundle = ClusterStateBundleUtil.makeBundle("distributor:2 storage:2",
                StateMapping.of("default", "distributor:2 storage:2 .0.s:d"),
                StateMapping.of("upsidedown", "distributor:2 .0.s:d storage:2"));
        ClusterFixture cf = ClusterFixture.forFlatCluster(2).bringEntireClusterUp().assignDummyRpcAddresses();
        f.broadcaster.handleNewClusterStates(stateBundle);
        f.broadcaster.broadcastNewStateBundleIfRequired(dbContextFrom(cf.cluster()), f.mockCommunicator);

        cf.cluster().getNodeInfo().forEach(nodeInfo -> verify(f.mockCommunicator).setSystemState(eq(stateBundle), eq(nodeInfo), any()));
    }

    @Test
    public void non_observed_startup_timestamps_are_published_per_bucket_space_state() {
        Fixture f = new Fixture();
        ClusterStateBundle stateBundle = ClusterStateBundleUtil.makeBundle("distributor:2 storage:2",
                StateMapping.of("default", "distributor:2 storage:2 .0.s:d"),
                StateMapping.of("upsidedown", "distributor:2 .0.s:d storage:2"));
        ClusterFixture cf = ClusterFixture.forFlatCluster(2).bringEntireClusterUp().assignDummyRpcAddresses();
        f.simulateNodePartitionedAwaySilently(cf);
        f.broadcaster.handleNewClusterStates(stateBundle);
        f.broadcaster.broadcastNewStateBundleIfRequired(dbContextFrom(cf.cluster()), f.mockCommunicator);

        clusterNodeInfos(cf.cluster(), Node.ofDistributor(1), Node.ofStorage(0), Node.ofStorage(1)).forEach(nodeInfo -> {
            // Only distributor 0 should observe startup timestamps
            verify(f.mockCommunicator).setSystemState(eq(stateBundle), eq(nodeInfo), any());
        });
        ClusterStateBundle expectedDistr0Bundle = ClusterStateBundleUtil.makeBundle("distributor:2 storage:2 .0.t:600 .1.t:700",
                StateMapping.of("default", "distributor:2 storage:2 .0.s:d .0.t:600 .1.t:700"),
                StateMapping.of("upsidedown", "distributor:2 .0.s:d storage:2 .0.t:600 .1.t:700"));
        verify(f.mockCommunicator).setSystemState(eq(expectedDistr0Bundle), eq(cf.cluster().getNodeInfo(Node.ofDistributor(0))), any());
    }

    private class MockSetClusterStateRequest extends SetClusterStateRequest {
        public MockSetClusterStateRequest(NodeInfo nodeInfo, int clusterStateVersion) {
            super(nodeInfo, clusterStateVersion);
        }
    }

    private class MockActivateClusterStateVersionRequest extends ActivateClusterStateVersionRequest {
        public MockActivateClusterStateVersionRequest(NodeInfo nodeInfo, int systemStateVersion) {
            super(nodeInfo, systemStateVersion);
        }
    }

    private void respondToSetClusterStateBundle(NodeInfo nodeInfo,
                                                ClusterStateBundle stateBundle,
                                                Communicator.Waiter<SetClusterStateRequest> waiter) {
        // Have to patch in that we've actually sent the bundle in the first place...
        nodeInfo.setClusterStateVersionBundleSent(stateBundle.getBaselineClusterState());

        var req =  new MockSetClusterStateRequest(nodeInfo, stateBundle.getVersion());
        req.setReply(new ClusterStateVersionSpecificRequest.Reply());
        waiter.done(req);
    }

    private void respondToActivateClusterStateVersion(NodeInfo nodeInfo,
                                                      ClusterStateBundle stateBundle,
                                                      Communicator.Waiter<ActivateClusterStateVersionRequest> waiter) {
        // Have to patch in that we've actually sent the bundle in the first place...
        nodeInfo.setClusterStateVersionActivationSent(stateBundle.getVersion());

        var req =  new MockActivateClusterStateVersionRequest(nodeInfo, stateBundle.getVersion());
        req.setReply(new ClusterStateVersionSpecificRequest.Reply());
        waiter.done(req);
    }

    private static class StateActivationFixture extends Fixture {
        ClusterStateBundle stateBundle;
        ClusterFixture cf;

        final ArgumentCaptor<Communicator.Waiter> d0Waiter;
        final ArgumentCaptor<Communicator.Waiter> d1Waiter;

        private StateActivationFixture(boolean enableDeferred) {
            super();
            stateBundle = ClusterStateBundleUtil
                    .makeBundleBuilder("version:123 distributor:2 storage:2")
                    .deferredActivation(enableDeferred)
                    .deriveAndBuild();
            cf = ClusterFixture.forFlatCluster(2).bringEntireClusterUp().assignDummyRpcAddresses();
            broadcaster.handleNewClusterStates(stateBundle);
            broadcaster.broadcastNewStateBundleIfRequired(dbContextFrom(cf.cluster()), mockCommunicator);

            d0Waiter = ArgumentCaptor.forClass(Communicator.Waiter.class);
            d1Waiter = ArgumentCaptor.forClass(Communicator.Waiter.class);
        }

        void expectSetSystemStateInvocationsToBothDistributors() {
            clusterNodeInfos(cf.cluster(), Node.ofDistributor(0), Node.ofDistributor(1)).forEach(nodeInfo -> {
                verify(mockCommunicator).setSystemState(eq(stateBundle), eq(nodeInfo),
                        (nodeInfo.getNodeIndex() == 0 ? d0Waiter : d1Waiter).capture());
            });
        }

        static StateActivationFixture withTwoPhaseEnabled() {
            return new StateActivationFixture(true);
        }

        static StateActivationFixture withTwoPhaseDisabled() {
            return new StateActivationFixture(false);
        }
    }

    @Test
    public void activation_not_sent_before_all_distributors_have_acked_state_bundle() {
        var f = StateActivationFixture.withTwoPhaseEnabled();
        var cf = f.cf;

        f.expectSetSystemStateInvocationsToBothDistributors();
        f.simulateBroadcastTick(cf);

        // Respond from distributor 0, but not yet from distributor 1
        respondToSetClusterStateBundle(cf.cluster.getNodeInfo(Node.ofDistributor(0)), f.stateBundle, f.d0Waiter.getValue());
        f.simulateBroadcastTick(cf);

        // No activations should be sent yet
        cf.cluster().getNodeInfo().forEach(nodeInfo -> {
            verify(f.mockCommunicator, times(0)).activateClusterStateVersion(
                    eq(123), eq(nodeInfo), any());
        });
        assertNull(f.broadcaster.getLastClusterStateBundleConverged());

        respondToSetClusterStateBundle(cf.cluster.getNodeInfo(Node.ofDistributor(1)), f.stateBundle, f.d1Waiter.getValue());
        f.simulateBroadcastTick(cf);

        // Activation should now be sent to _all_ nodes (distributor and storage)
        cf.cluster().getNodeInfo().forEach(nodeInfo -> {
            verify(f.mockCommunicator).activateClusterStateVersion(eq(123), eq(nodeInfo), any());
        });
        // But not converged yet, as activations have not been ACKed
        assertNull(f.broadcaster.getLastClusterStateBundleConverged());
    }

    @Test
    public void state_bundle_not_considered_converged_until_activation_acked_by_all_distributors() {
        var f = StateActivationFixture.withTwoPhaseEnabled();
        var cf = f.cf;

        f.expectSetSystemStateInvocationsToBothDistributors();
        f.simulateBroadcastTick(cf);
        // ACK state bundle from both distributors
        respondToSetClusterStateBundle(cf.cluster.getNodeInfo(Node.ofDistributor(0)), f.stateBundle, f.d0Waiter.getValue());
        respondToSetClusterStateBundle(cf.cluster.getNodeInfo(Node.ofDistributor(1)), f.stateBundle, f.d1Waiter.getValue());

        f.simulateBroadcastTick(cf);

        final var d0ActivateWaiter = ArgumentCaptor.forClass(Communicator.Waiter.class);
        final var d1ActivateWaiter = ArgumentCaptor.forClass(Communicator.Waiter.class);

        clusterNodeInfos(cf.cluster(), Node.ofDistributor(0), Node.ofDistributor(1)).forEach(nodeInfo -> {
            verify(f.mockCommunicator).activateClusterStateVersion(eq(123), eq(nodeInfo),
                    (nodeInfo.getNodeIndex() == 0 ? d0ActivateWaiter : d1ActivateWaiter).capture());
        });

        respondToActivateClusterStateVersion(cf.cluster.getNodeInfo(Node.ofDistributor(0)),
                                             f.stateBundle, d0ActivateWaiter.getValue());
        f.simulateBroadcastTick(cf);

        assertNull(f.broadcaster.getLastClusterStateBundleConverged()); // Not yet converged

        respondToActivateClusterStateVersion(cf.cluster.getNodeInfo(Node.ofDistributor(1)),
                                             f.stateBundle, d1ActivateWaiter.getValue());
        f.simulateBroadcastTick(cf);

        // Finally, all distributors have ACKed the version! State is marked as converged.
        assertEquals(f.stateBundle, f.broadcaster.getLastClusterStateBundleConverged());
    }

    @Test
    public void activation_not_sent_if_deferred_activation_is_disabled_in_state_bundle() {
        var f = StateActivationFixture.withTwoPhaseDisabled();
        var cf = f.cf;

        f.expectSetSystemStateInvocationsToBothDistributors();
        f.simulateBroadcastTick(cf);
        // ACK state bundle from both distributors
        respondToSetClusterStateBundle(cf.cluster.getNodeInfo(Node.ofDistributor(0)), f.stateBundle, f.d0Waiter.getValue());
        respondToSetClusterStateBundle(cf.cluster.getNodeInfo(Node.ofDistributor(1)), f.stateBundle, f.d1Waiter.getValue());
        f.simulateBroadcastTick(cf);

        // At this point the cluster state shall be considered converged.
        assertEquals(f.stateBundle, f.broadcaster.getLastClusterStateBundleConverged());

        // No activations shall have been sent.
        clusterNodeInfos(cf.cluster(), Node.ofDistributor(0), Node.ofDistributor(1)).forEach(nodeInfo -> {
            verify(f.mockCommunicator, times(0)).activateClusterStateVersion(eq(123), eq(nodeInfo), any());
        });
    }

}
