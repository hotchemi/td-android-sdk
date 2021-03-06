package com.treasuredata.android;

import com.fasterxml.jackson.jr.ob.JSON;
import io.keen.client.java.KeenCallback;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.core.Is.is;
import static org.junit.Assert.*;

public class TDClientTest
{
    private final static String APIKEY = "9999/1qaz2wsx3edc4rfv5tgb6yhn";
    private final static JSON JSON = new JSON();

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private File cacheDir;
    private MockWebServer server;

    @Before
    public void setUp()
            throws IOException
    {
        cacheDir = temporaryFolder.getRoot();

        server = new MockWebServer();

        TDHttpHandler.disableEventCompression();
    }

    @After
    public void tearDown()
            throws IOException
    {
        TDHttpHandler.enableEventCompression();

        server.shutdown();
    }

    private void sendQueuedEventsAndAssert(TDClient client, final List<Map<String, List<Map<String, Object>>>> expects)
            throws Exception
    {
        final CountDownLatch latch = new CountDownLatch(1);
        client.sendQueuedEventsAsync(null, new KeenCallback() {
            @Override
            public void onSuccess()
            {
                try {
                    assertThat(server.getRequestCount(), is(expects.size()));

                    for (Map<String, List<Map<String, Object>>> expected : expects) {
                        RecordedRequest recordedRequest = server.takeRequest();
                        assertThat(recordedRequest.getMethod(), is("POST"));
                        assertThat(recordedRequest.getHeader("X-TD-Write-Key"), is(APIKEY));
                        assertThat(recordedRequest.getHeader("X-TD-Data-Type"), is("k"));
                        Map<String, Object> requests = JSON.mapFrom(recordedRequest.getBody().inputStream());
                        assertThat(requests.size(), is(expected.size()));
                        for (Map.Entry<String, List<Map<String, Object>>> exp : expected.entrySet()) {
                            List<Map<String, Object>> events = (List<Map<String, Object>>) requests.get(exp.getKey());
                            assertThat(events.size(), is(exp.getValue().size()));
                            int i = 0;
                            for (Map<String, Object> expectedEvent : exp.getValue()) {
                                Map<String, Object> event = events.get(i);
                                for (Map.Entry<String, Object> keyAndValue : expectedEvent.entrySet()) {
                                    assertThat(event.get(keyAndValue.getKey()), is(keyAndValue.getValue()));
                                }
                                i++;
                            }
                        }
                    }
                    latch.countDown();
                }
                catch (Exception e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void onFailure(Exception e)
            {
                e.printStackTrace();
                assertTrue(false);
            }
        });
        assertTrue(latch.await(10, TimeUnit.SECONDS));
    }

    @Test
    public void sendToSingleTable()
            throws Exception
    {
        server.enqueue(new MockResponse().setBody("{\"db0.tbl0\":[{\"success\":true},{\"success\":true}]}"));
        server.start();
        TDClient.setApiEndpoint(String.format("http://127.0.0.1:%d", server.getPort()));
        TDClient client = new TDClient(APIKEY, cacheDir);

        HashMap<String, Object> event0 = new HashMap<String, Object>();
        event0.put("name", "Foo");
        event0.put("age", 42);
        client.queueEvent("db0.tbl0", event0);

        HashMap<String, Object> event1 = new HashMap<String, Object>();
        event1.put("name", "Bar");
        event1.put("age", 99);
        client.queueEvent("db0.tbl0", event1);

        Map<String, List<Map<String, Object>>> expected = new HashMap<String, List<Map<String, Object>>>();
        expected.put("db0.tbl0", Arrays.<Map<String, Object>>asList(event0, event1));

        sendQueuedEventsAndAssert(client, Arrays.asList(expected));
    }

    @Test
    public void sendToTwoTables()
            throws Exception
    {
        server.enqueue(new MockResponse().setBody(
                "{\"db0.tbl0\":[{\"success\":true}]," +
                 "\"db1.tbl1\":[{\"success\":true}]}"));
        server.start();
        TDClient.setApiEndpoint(String.format("http://127.0.0.1:%d", server.getPort()));
        TDClient client = new TDClient(APIKEY, cacheDir);

        HashMap<String, Object> event0 = new HashMap<String, Object>();
        event0.put("name", "Foo");
        event0.put("age", 42);
        client.queueEvent("db0.tbl0", event0);

        HashMap<String, Object> event1 = new HashMap<String, Object>();
        event1.put("name", "Bar");
        event1.put("age", 99);
        client.queueEvent("db1.tbl1", event1);

        Map<String, List<Map<String, Object>>> expected = new HashMap<String, List<Map<String, Object>>>();
        expected.put("db0.tbl0", Arrays.<Map<String, Object>>asList(event0));
        expected.put("db1.tbl1", Arrays.<Map<String, Object>>asList(event1));

        sendQueuedEventsAndAssert(client, Arrays.asList(expected));
    }

    @Test
    public void sendToSingleTableWithLimitedUploadedEvents()
            throws Exception
    {
        server.enqueue(new MockResponse().setBody("{\"db0.tbl0\":[{\"success\":true},{\"success\":true},{\"success\":true}]}"));
        server.enqueue(new MockResponse().setBody("{\"db0.tbl0\":[{\"success\":true}]}"));
        server.start();
        TDClient.setApiEndpoint(String.format("http://127.0.0.1:%d", server.getPort()));
        TDClient client = new TDClient(APIKEY, cacheDir);
        client.setMaxUploadEventsAtOnce(3);

        HashMap<String, Object> event0 = new HashMap<String, Object>();
        event0.put("name", "Foo");
        event0.put("age", 42);
        client.queueEvent("db0.tbl0", event0);

        HashMap<String, Object> event1 = new HashMap<String, Object>();
        event1.put("name", "Bar");
        event1.put("age", 99);
        client.queueEvent("db0.tbl0", event1);

        HashMap<String, Object> event2 = new HashMap<String, Object>();
        event2.put("name", "Baz");
        event2.put("age", 1);
        client.queueEvent("db0.tbl0", event2);

        HashMap<String, Object> event3 = new HashMap<String, Object>();
        event3.put("name", "zzz");
        event3.put("age", 111);
        client.queueEvent("db0.tbl0", event3);

        Map<String, List<Map<String, Object>>> expected0 = new HashMap<String, List<Map<String, Object>>>();
        expected0.put("db0.tbl0", Arrays.<Map<String, Object>>asList(event0, event1, event2));

        Map<String, List<Map<String, Object>>> expected1 = new HashMap<String, List<Map<String, Object>>>();
        expected1.put("db0.tbl0", Arrays.<Map<String, Object>>asList(event3));

        sendQueuedEventsAndAssert(client, Arrays.asList(expected0, expected1));
    }

    @Test
    public void sendToTwoTablesWithLimitedUploadedEvents()
            throws Exception
    {
        server.enqueue(new MockResponse().setBody(
                "{\"db0.tbl0\":[{\"success\":true},{\"success\":true}]," +
                 "\"db1.tbl1\":[{\"success\":true}]}"));
        server.enqueue(new MockResponse().setBody(
                "{\"db1.tbl1\":[{\"success\":true}]}"));
        server.start();
        TDClient.setApiEndpoint(String.format("http://127.0.0.1:%d", server.getPort()));
        TDClient client = new TDClient(APIKEY, cacheDir);
        client.setMaxUploadEventsAtOnce(3);

        HashMap<String, Object> event0 = new HashMap<String, Object>();
        event0.put("name", "Foo");
        event0.put("age", 42);
        client.queueEvent("db0.tbl0", event0);

        HashMap<String, Object> event1 = new HashMap<String, Object>();
        event1.put("name", "Bar");
        event1.put("age", 99);
        client.queueEvent("db0.tbl0", event1);

        HashMap<String, Object> event2 = new HashMap<String, Object>();
        event2.put("name", "Baz");
        event2.put("age", 1);
        client.queueEvent("db1.tbl1", event2);

        HashMap<String, Object> event3 = new HashMap<String, Object>();
        event3.put("name", "Zzz");
        event3.put("age", 111);
        client.queueEvent("db1.tbl1", event3);

        Map<String, List<Map<String, Object>>> expected0 = new HashMap<String, List<Map<String, Object>>>();
        expected0.put("db0.tbl0", Arrays.<Map<String, Object>>asList(event0, event1));
        expected0.put("db1.tbl1", Arrays.<Map<String, Object>>asList(event2));

        Map<String, List<Map<String, Object>>> expected1 = new HashMap<String, List<Map<String, Object>>>();
        expected1.put("db1.tbl1", Arrays.<Map<String, Object>>asList(event3));

        sendQueuedEventsAndAssert(client, Arrays.asList(expected0, expected1));
    }
}