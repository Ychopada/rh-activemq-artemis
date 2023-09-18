/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.artemis.tests.integration.openwire;

import javax.jms.Connection;
import javax.jms.JMSException;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.Queue;
import javax.jms.Session;
import javax.jms.TextMessage;

import java.io.PrintStream;
import java.lang.invoke.MethodHandles;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.activemq.ActiveMQConnection;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.ActiveMQPrefetchPolicy;
import org.apache.activemq.RedeliveryPolicy;
import org.apache.activemq.artemis.api.core.QueueConfiguration;
import org.apache.activemq.artemis.api.core.RoutingType;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.cli.commands.tools.PrintData;
import org.apache.activemq.artemis.core.settings.impl.AddressSettings;
import org.apache.activemq.artemis.utils.Wait;
import org.apache.activemq.command.ActiveMQQueue;
import org.apache.activemq.transport.failover.FailoverTransport;
import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PrefetchRedeliveryCountOpenwireTest extends OpenWireTestBase {

   private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

   @Override
   public void setUp() throws Exception {
      realStore = true;
      super.setUp();
   }

   @Override
   protected void configureAddressSettings(Map<String, AddressSettings> addressSettingsMap) {
      super.configureAddressSettings(addressSettingsMap);
      // force send to dlq early
      addressSettingsMap.put("exampleQueue", new AddressSettings().setAutoCreateQueues(false).setAutoCreateAddresses(false).setDeadLetterAddress(new SimpleString("ActiveMQ.DLQ")).setAutoCreateAddresses(true).setMaxDeliveryAttempts(2));
      // force send to dlq late
      addressSettingsMap.put("exampleQueueTwo", new AddressSettings().setAutoCreateQueues(false).setAutoCreateAddresses(false).setDeadLetterAddress(new SimpleString("ActiveMQ.DLQ")).setAutoCreateAddresses(true).setMaxDeliveryAttempts(4000));
   }

   @Test(timeout = 60_000)
   public void testConsumerSingleMessageLoopExclusive() throws Exception {
      doTestConsumerSingleMessageLoop(true);
   }

   @Test(timeout = 60_000)
   public void testConsumerSingleMessageLoopNonExclusive() throws Exception {
      doTestConsumerSingleMessageLoop(false);
   }

   public void  doTestConsumerSingleMessageLoop(boolean exclusive) throws Exception {
      Connection exConn = null;

      SimpleString durableQueue = new SimpleString("exampleQueue");
      this.server.createQueue(new QueueConfiguration(durableQueue).setRoutingType(RoutingType.ANYCAST).setExclusive(exclusive));

      try {
         ActiveMQConnectionFactory exFact = new ActiveMQConnectionFactory();
         exFact.setWatchTopicAdvisories(false);

         Queue queue = new ActiveMQQueue("exampleQueue");

         exConn = exFact.createConnection();

         exConn.start();

         Session session = exConn.createSession(false, Session.AUTO_ACKNOWLEDGE);

         MessageProducer producer = session.createProducer(queue);

         TextMessage message = session.createTextMessage("This is a text message");

         int numMessages = 20;
         for (int i = 0; i < numMessages; i++) {
            producer.send(message);
         }

         for (int i = 0; i < numMessages; i++) {
            // consumer per message
            MessageConsumer messageConsumer = session.createConsumer(queue);

            TextMessage messageReceived = (TextMessage) messageConsumer.receive(5000);
            Assert.assertNotNull(messageReceived);

            assertEquals("This is a text message", messageReceived.getText());
            messageConsumer.close();
         }
      } finally {
         if (exConn != null) {
            exConn.close();
         }
      }
   }

   @Test(timeout = 60_000)
   public void testExclusiveConsumerOrderOnReconnectionLargePrefetch() throws Exception {
      Connection exConn = null;

      SimpleString durableQueue = new SimpleString("exampleQueueTwo");
      this.server.createQueue(new QueueConfiguration(durableQueue).setRoutingType(RoutingType.ANYCAST).setExclusive(true));

      try {
         ActiveMQConnectionFactory exFact = new ActiveMQConnectionFactory();
         exFact.setWatchTopicAdvisories(false);

         ActiveMQPrefetchPolicy prefetchPastMaxDeliveriesInLoop = new ActiveMQPrefetchPolicy();
         prefetchPastMaxDeliveriesInLoop.setAll(2000);
         exFact.setPrefetchPolicy(prefetchPastMaxDeliveriesInLoop);

         RedeliveryPolicy redeliveryPolicy = new RedeliveryPolicy();
         redeliveryPolicy.setMaximumRedeliveries(4000);
         exFact.setRedeliveryPolicy(redeliveryPolicy);

         Queue queue = new ActiveMQQueue("exampleQueueTwo");

         exConn = exFact.createConnection();

         exConn.start();

         Session session = exConn.createSession(true, Session.AUTO_ACKNOWLEDGE);

         MessageProducer producer = session.createProducer(queue);

         TextMessage message = session.createTextMessage("This is a text message");

         int numMessages = 2000;
         for (int i = 0; i < numMessages; i++) {
            message.setIntProperty("SEQ", i);
            producer.send(message);
         }
         session.commit();
         exConn.close();

         final int batch = 100;
         for (int i = 0; i < numMessages; i += batch) {
            // connection per batch
            exConn = exFact.createConnection();
            exConn.start();

            session = exConn.createSession(true, Session.SESSION_TRANSACTED);

            MessageConsumer messageConsumer = session.createConsumer(queue);
            TextMessage messageReceived = null;
            for (int j = 0; j < batch; j++) { // a small batch
               messageReceived = (TextMessage) messageConsumer.receive(5000);
               Assert.assertNotNull("null @ i=" + i, messageReceived);
               Assert.assertEquals(i + j, messageReceived.getIntProperty("SEQ"));

               assertEquals("This is a text message", messageReceived.getText());
            }
            session.commit();

            // force a local socket close such that the broker sees an exception on the connection and fails the consumer via close
            ((FailoverTransport)((org.apache.activemq.ActiveMQConnection)exConn).getTransport().narrow(FailoverTransport.class)).stop();
            exConn.close();
         }
      } finally {
         if (exConn != null) {
            exConn.close();
         }
      }
   }

   @Test(timeout = 60_000)
   public void testExclusiveConsumerTransactionalBatchOnReconnectionLargePrefetch() throws Exception {
      Connection exConn = null;

      SimpleString durableQueue = new SimpleString("exampleQueueTwo");
      this.server.createQueue(new QueueConfiguration(durableQueue).setRoutingType(RoutingType.ANYCAST).setExclusive(true));
      AtomicInteger batchConsumed = new AtomicInteger(0);

      try {
         ActiveMQConnectionFactory exFact = new ActiveMQConnectionFactory();
         exFact.setWatchTopicAdvisories(false);

         RedeliveryPolicy redeliveryPolicy = new RedeliveryPolicy();
         redeliveryPolicy.setMaximumRedeliveries(4000);
         exFact.setRedeliveryPolicy(redeliveryPolicy);

         Queue queue = new ActiveMQQueue("exampleQueueTwo");

         exConn = exFact.createConnection();

         exConn.start();

         Session session = exConn.createSession(false, Session.AUTO_ACKNOWLEDGE);

         MessageProducer producer = session.createProducer(queue);

         TextMessage message = session.createTextMessage("This is a text message");

         ExecutorService executorService = Executors.newSingleThreadExecutor();
         int numMessages = 600;
         for (int i = 0; i < numMessages; i++) {
            message.setIntProperty("SEQ", i);
            producer.send(message);
         }
         session.close();
         exConn.close();

         final int batch = numMessages;
         AtomicInteger commits = new AtomicInteger(0);
         AtomicBoolean done = new AtomicBoolean(false);
         while (!done.get()) {
            // connection per batch attempt
            exConn = exFact.createConnection();
            ((ActiveMQConnection) exConn).setCloseTimeout(1); // so rollback on close won't block after socket close exception

            exConn.start();

            session = exConn.createSession(true, Session.SESSION_TRANSACTED);

            MessageConsumer messageConsumer = session.createConsumer(queue);
            TextMessage messageReceived = null;
            for (int j = 0; j < batch; j++) {
               messageReceived = (TextMessage) messageConsumer.receive(2000);
               if (messageReceived == null) {
                  done.set(true);
                  break;
               }
               batchConsumed.incrementAndGet();
               assertEquals("This is a text message", messageReceived.getText());
            }

            // arrange concurrent commit - ack/commit
            // with server side error, potential for ack/commit and close-on-fail to contend
            final CountDownLatch latch = new CountDownLatch(1);
            Session finalSession = session;
            executorService.submit(new Runnable() {
               @Override
               public void run() {
                  try {
                     latch.countDown();
                     finalSession.commit();
                     commits.incrementAndGet();

                  } catch (JMSException e) {
                  }
               }
            });

            latch.await(1, TimeUnit.SECONDS);
            // force a local socket close such that the broker sees an exception on the connection and fails the consumer via serverConsumer close
            ((FailoverTransport) ((org.apache.activemq.ActiveMQConnection) exConn).getTransport().narrow(FailoverTransport.class)).stop();
            exConn.close();
         }
      } finally {
         if (exConn != null) {
            exConn.close();
         }
      }

      logger.info("Done after: {}, queue: {}", batchConsumed.get(), server.locateQueue(durableQueue));
      try {
         Wait.assertEquals(0L, () -> server.locateQueue(durableQueue).getDeliveringCount(), 1000);
      } catch (Throwable e) {

         final AtomicBoolean doOut = new AtomicBoolean(false);
         PrintStream out = new PrintStream(System.out) {

            @Override
            public void println(String s) {
               if (doOut.get()) {
                  super.println(s);
               } else {
                  if (s.startsWith("### Failed Transactions")) {
                     doOut.set(true);
                     super.println(s);
                  }
               }
            }
         };
         PrintData.printData(server.getConfiguration().getBindingsLocation(),server.getConfiguration().getJournalLocation(),server.getConfiguration().getPagingLocation(), out, true, true, true, false, -1);

         throw e;
      }
   }

}
