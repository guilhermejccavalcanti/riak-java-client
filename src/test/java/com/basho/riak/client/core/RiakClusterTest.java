/*
 * Copyright 2013 Basho Technologies Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.basho.riak.client.core;

import com.google.protobuf.Message;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.verification.VerificationMode;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.powermock.reflect.Whitebox;

import java.net.UnknownHostException;
import java.util.List;
import java.util.Random;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.*;



/**
 *
 * @author Brian Roach <roach at basho dot com>
 */
@RunWith(PowerMockRunner.class)
@PrepareForTest(FutureOperation.class)
public class RiakClusterTest
{
    @Test
    public void builderCreatesCluster() throws UnknownHostException
    {
        RiakNode.Builder nodeBuilder = new RiakNode.Builder();
        RiakCluster cluster = new RiakCluster.Builder(nodeBuilder.build()).build();
        assertTrue(!cluster.getNodes().isEmpty());
    }
    
    @Test
    public void addNodeToCluster() throws UnknownHostException
    {
        NodeManager nodeManager = mock(NodeManager.class);
        RiakNode node = mock(RiakNode.class);
        RiakNode.Builder nodeBuilder = spy(new RiakNode.Builder());
        doReturn(node).when(nodeBuilder).build();
        
        RiakCluster cluster = new RiakCluster.Builder(nodeBuilder.build()).withNodeManager(nodeManager).build();
        cluster.addNode(nodeBuilder.build());
        assertEquals(2, cluster.getNodes().size());
        verify(nodeManager).addNode(node);
    }
    
    @Test
    public void removeNodeFromCluster() throws UnknownHostException
    {
        NodeManager nodeManager = mock(NodeManager.class);
        RiakNode node = mock(RiakNode.class);
        RiakNode.Builder nodeBuilder = spy(new RiakNode.Builder());
        doReturn(node).when(nodeBuilder).build();
        doReturn(true).when(nodeManager).removeNode(node);
        
        RiakCluster cluster = new RiakCluster.Builder(nodeBuilder.build()).withNodeManager(nodeManager).build();
        assertTrue(cluster.removeNode(node));
        verify(nodeManager).removeNode(node);
        assertEquals(0, cluster.getNodes().size());
    }
    
    @Test
    public void allNodesShutdownStopsCluster() throws UnknownHostException
    {
        NodeManager nodeManager = mock(NodeManager.class);
        RiakNode node = mock(RiakNode.class);
        RiakNode.Builder nodeBuilder = spy(new RiakNode.Builder());
        doReturn(node).when(nodeBuilder).build();
        doReturn(true).when(nodeManager).removeNode(node);
        
        RiakCluster cluster = new RiakCluster.Builder(nodeBuilder.build()).withNodeManager(nodeManager).build();
        cluster.nodeStateChanged(node, RiakNode.State.SHUTDOWN);
        RiakCluster.State state = Whitebox.getInternalState(cluster, "state");
        assertEquals(state, RiakCluster.State.SHUTDOWN);
    }
    
    @Test
    @SuppressWarnings("unchecked")
    public void clusterExecutesOperation() throws UnknownHostException
    {
        NodeManager nodeManager = mock(NodeManager.class);
        FutureOperation operation = PowerMockito.mock(FutureOperation.class);
        RiakNode node = mock(RiakNode.class);
        RiakNode.Builder nodeBuilder = spy(new RiakNode.Builder());
        doReturn(node).when(nodeBuilder).build();
        doReturn(true).when(node).execute(operation);
        
        RiakCluster cluster = new RiakCluster.Builder(nodeBuilder.build()).withNodeManager(nodeManager).build();
        Whitebox.setInternalState(cluster, "state", RiakCluster.State.RUNNING);
        cluster.execute(operation);
        assertEquals(1, cluster.inFlightCount());
        verify(nodeManager).executeOnNode(operation, null);
        cluster.operationComplete(operation, 2);
        assertEquals(0, cluster.inFlightCount());
        
        cluster.execute(operation);
        cluster.operationFailed(operation, 1);
        LinkedBlockingQueue<?> retryQueue = Whitebox.getInternalState(cluster, "retryQueue");
        assertEquals(1, retryQueue.size());
    }

    @Test
    public void nodeOperationQueue() throws Exception
    {
        final int OPERATION_QUEUE = 2;

        NodeManager nodeManager = mock(NodeManager.class);
        FutureOperation operation1 = new FutureOperationImpl();
        FutureOperation operation2 = new FutureOperationImpl();
        FutureOperation operation3 = new FutureOperationImpl();

        RiakNode node = mock(RiakNode.class);
        RiakNode.Builder nodeBuilder = spy(new RiakNode.Builder());
        doReturn(node).when(nodeBuilder).build();

        doReturn(false).when(nodeManager).executeOnNode(operation1, null);

        // Build cluster, check our initial states
        RiakCluster cluster = new RiakCluster.Builder(nodeBuilder.build())
                                    .withNodeManager(nodeManager)
                                    .withOperationQueueMaxDepth(OPERATION_QUEUE).build();
        Whitebox.setInternalState(cluster, "state", RiakCluster.State.RUNNING);

        boolean queueEnabled = Whitebox.getInternalState(cluster, "queueOperations");
        assertTrue(queueEnabled);

        // Run our operations, fill up queue
        // 3rd operation should fail, and first 2 should register as in-flight.

        RiakFuture future1 = cluster.execute(operation1);
        RiakFuture future2 = cluster.execute(operation2);
        RiakFuture future3 = cluster.execute(operation3);

        assertEquals(2, cluster.inFlightCount());
        // 1st item in queue is run every time another is added.
        // If it fails to make a connection it's put back at the beginning of the queue.
        verify(nodeManager, times(3)).executeOnNode(operation1, null);
        verify(nodeManager, times(0)).executeOnNode(operation2, null);
        verify(nodeManager, times(0)).executeOnNode(operation3, null);

        LinkedBlockingDeque < FutureOperation > operationQueue = Whitebox.getInternalState(cluster, "operationQueue");
        RiakCluster.State state = Whitebox.getInternalState(cluster, "state");
        assertEquals(2, operationQueue.size());
        assertEquals(RiakCluster.State.QUEUING, state);

        assertEquals(NoNodesAvailableException.class, future3.cause().getClass());
        assertEquals("No Nodes Available, and Operation Queue at Max Depth", future3.cause().getMessage());


        // Come back from a full queue
        doReturn(true).when(nodeManager).executeOnNode(operation1, null);
        doReturn(true).when(nodeManager).executeOnNode(operation2, null);

        // Act like the Queue Drain Thread
        Whitebox.invokeMethod(cluster, "queueDrainOperation");

        operationQueue = Whitebox.getInternalState(cluster, "operationQueue");
        state = Whitebox.getInternalState(cluster, "state");
        assertEquals(1, operationQueue.size());
        assertEquals(RiakCluster.State.QUEUING, state);
        verify(nodeManager, times(4)).executeOnNode(operation1, null);

        // Run a new operation to ensure it gets queued after the old work
        FutureOperation operation4 = new FutureOperationImpl();
        doReturn(true).when(node).execute(operation4);
        doReturn(true).when(nodeManager).executeOnNode(operation4, null);
        RiakFuture future4 = cluster.execute(operation4);

        operationQueue = Whitebox.getInternalState(cluster, "operationQueue");
        state = Whitebox.getInternalState(cluster, "state");
        assertEquals(1, operationQueue.size());
        assertEquals(RiakCluster.State.QUEUING, state);
        assertEquals(operation4, operationQueue.peek());
        verify(nodeManager, times(1)).executeOnNode(operation2, null);

        // Run remainder of Queue Drain operations.
        Whitebox.invokeMethod(cluster, "queueDrainOperation");

        operationQueue = Whitebox.getInternalState(cluster, "operationQueue");
        state = Whitebox.getInternalState(cluster, "state");
        assertEquals(0, operationQueue.size());
        assertEquals(RiakCluster.State.RUNNING, state);
        verify(nodeManager, times(1)).executeOnNode(operation4, null);

    }

    private class FutureOperationImpl extends FutureOperation<String, Message, Void>
    {
        @Override
        protected String convert(List<Message> rawResponse)
        {
            return "value";
        }

        @Override
        protected Message decode(RiakMessage rawMessage)
        {
            return null;
        }

        @Override
        protected RiakMessage createChannelMessage()
        {
            return new RiakMessage((byte) 0, new byte[0]);
        }

        @Override
        public Void getQueryInfo()
        {
            return null;
        }
    }
}
