/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.jackrabbit.oak.benchmark;

import static javax.jcr.observation.Event.NODE_ADDED;
import static javax.jcr.observation.Event.NODE_MOVED;
import static javax.jcr.observation.Event.NODE_REMOVED;
import static javax.jcr.observation.Event.PERSIST;
import static javax.jcr.observation.Event.PROPERTY_ADDED;
import static javax.jcr.observation.Event.PROPERTY_CHANGED;
import static javax.jcr.observation.Event.PROPERTY_REMOVED;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import javax.jcr.Node;
import javax.jcr.Repository;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.SimpleCredentials;
import javax.jcr.observation.EventIterator;
import javax.jcr.observation.EventListener;
import javax.jcr.observation.ObservationManager;

import org.apache.jackrabbit.commons.JcrUtils;
import org.apache.jackrabbit.oak.fixture.RepositoryFixture;

public class ObservationTest extends Benchmark {
    public static final int EVENT_TYPES = NODE_ADDED | NODE_REMOVED | NODE_MOVED |
            PROPERTY_ADDED | PROPERTY_REMOVED | PROPERTY_CHANGED | PERSIST;
    private static final int EVENTS_PER_NODE = 2; // NODE_ADDED and PROPERTY_ADDED
    private static final int SAVE_INTERVAL = 100;
    private static final int OUTPUT_RESOLUTION = 100;

    @Override
    public void run(Iterable<RepositoryFixture> fixtures) {
        for (RepositoryFixture fixture : fixtures) {
            if (fixture.isAvailable(1)) {
                System.out.format("%s: Observation throughput benchmark%n", fixture);
                try {
                    Repository[] cluster = fixture.setUpCluster(1);
                    try {
                        run(cluster[0]);
                    } finally {
                        fixture.tearDownCluster();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void run(Repository repository) throws RepositoryException, ExecutionException, InterruptedException {
        Session session = repository.login(new SimpleCredentials("admin", "admin".toCharArray()));
        try {
            observationThroughput(repository, session.getWorkspace().getObservationManager());
        } finally {
            session.logout();
        }
    }

    public void observationThroughput(final Repository repository, ObservationManager observationManager)
            throws RepositoryException, InterruptedException, ExecutionException {
        long t = 0;
        final AtomicInteger eventCount = new AtomicInteger();
        final AtomicInteger nodeCount = new AtomicInteger();

        EventListener listener = new EventListener() {
            @Override
            public void onEvent(EventIterator events) {
                for (; events.hasNext(); events.nextEvent()) {
                    eventCount.incrementAndGet();
                }
            }
        };

        try {
            observationManager.addEventListener(listener, EVENT_TYPES, "/", true, null, null, false);
            Future<?> createNodes = Executors.newSingleThreadExecutor().submit(new Runnable() {
                private final Session session = repository.login(new SimpleCredentials("admin", "admin".toCharArray()));

                @Override
                public void run() {
                    try {
                        Node testRoot = session.getRootNode().addNode("observationBenchmark");
                        createChildren(testRoot, 100);
                        for (Node m : JcrUtils.getChildNodes(testRoot)) {
                            createChildren(m, 100);
                            for (Node n : JcrUtils.getChildNodes(m)) {
                                createChildren(n, 5);
                            }
                        }
                        session.save();
                    } catch (RepositoryException e) {
                        throw new RuntimeException(e);
                    } finally {
                        session.logout();
                    }
                }

                private void createChildren(Node node, int count) throws RepositoryException {
                    for (int c = 0; c < count; c++) {
                        node.addNode("n" + c);
                        if (nodeCount.incrementAndGet() % SAVE_INTERVAL == 0) {
                            node.getSession().save();
                        }
                    }
                }
            });

            System.out.println("ms      #node   nodes/s #event  event/s event ratio");
            while (!createNodes.isDone() || (eventCount.get() < nodeCount.get() * EVENTS_PER_NODE)) {
                long t0 = System.currentTimeMillis();
                Thread.sleep(OUTPUT_RESOLUTION);
                t += System.currentTimeMillis() - t0;

                int nc = nodeCount.get();
                int ec = eventCount.get();

                double nps = (double) nc / t * 1000;
                double eps = (double) ec / t * 1000;
                double epn = (double) ec / nc / EVENTS_PER_NODE;

                System.out.format("%7d %7d %7.1f %7d %7.1f %1.2f%n", t, nc, nps, ec, eps, epn);
            }
            createNodes.get();
        } finally {
            observationManager.removeEventListener(listener);
        }
    }

}
