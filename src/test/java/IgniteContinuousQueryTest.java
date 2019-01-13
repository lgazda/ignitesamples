import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.IgniteDataStreamer;
import org.apache.ignite.Ignition;
import org.apache.ignite.cache.CacheInterceptor;
import org.apache.ignite.cache.query.ContinuousQuery;
import org.apache.ignite.configuration.CacheConfiguration;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.lang.IgniteBiTuple;
import org.apache.ignite.resources.IgniteInstanceResource;
import org.apache.ignite.spi.discovery.tcp.TcpDiscoverySpi;
import org.apache.ignite.spi.discovery.tcp.ipfinder.vm.TcpDiscoveryVmIpFinder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.cache.Cache;
import javax.cache.configuration.Factory;
import javax.cache.event.CacheEntryEvent;
import javax.cache.event.CacheEntryEventFilter;
import javax.cache.event.CacheEntryListenerException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static java.util.Arrays.asList;
import static java.util.Collections.singleton;
import static org.apache.ignite.cache.CacheMode.REPLICATED;

/**
 * Demonstrate different continuous query local listener behavior depending on query .setLocal, and different query deployment type.
 */
public class IgniteContinuousQueryTest {
    private static final Logger LOG = LoggerFactory.getLogger(IgniteContinuousQueryTest.class);

    private static final String SAMPLE_CACHE_NAME = "TestCache";

    @Test
    public void startContinuousQueryWithFilterOnlyOnOneNode_StartNodesAtOnce_LocalQueryFalse() {

        Ignite igniteServer1 = startIgniteServer("server1");
        Ignite igniteServer2 = startIgniteServer("server2");
        Ignite igniteServer3 = startIgniteServer("server3");

        Ignite igniteClient = startIgniteClient();

        //initial cluster
        startContinuousQueryForLocalQueryAndFilterValue(false, true, igniteServer1);
        streamSomeDataWithIndexRange(igniteClient, 0, 2);

       // sleep(120_000);
        System.gc();


        /* Brake point here and check memory
           com.intellij.debugger.memory.ui.JavaTypeInfo@1290	shows 1277988 CacheContinuousQueryEntry
        */
        closeIgnites(igniteClient, igniteServer1, igniteServer2, igniteServer3);
    }

    @Test
    public void startContinuousQueryWithFilterOnlyOnOneNode_StartNodesAtOnce_LocalQueryTrue() {

        Ignite igniteServer1 = startIgniteServer("server1");
        Ignite igniteServer2 = startIgniteServer("server2");
        Ignite igniteServer3 = startIgniteServer("server3");

        Ignite igniteClient = startIgniteClient();

        //initial cluster
        startContinuousQueryForLocalQueryAndFilterValue(true, true, igniteServer1);
        streamSomeDataWithIndexRange(igniteClient, 0, 2);

        // sleep(120_000);
        System.gc();


        /* Brake point here and check memory
           com.intellij.debugger.memory.ui.JavaTypeInfo@1290	shows 1277988 CacheContinuousQueryEntry
        */
        closeIgnites(igniteClient, igniteServer1, igniteServer2, igniteServer3);
    }

    @Test
    public void startContinuousQueryWithFilterOnlyOnOneNode_StartNodesIncrementally_LocalQueryFalse() {

        Ignite igniteServer1 = startIgniteServer("server1");
        Ignite igniteServer2 = startIgniteServer("server2");

        Ignite igniteClient = startIgniteClient();

        //initial cluster
        startContinuousQueryForLocalQueryAndFilterValue(false, true, igniteServer1);
        streamSomeDataWithIndexRange(igniteClient, 0, 1);

        Ignite igniteServer3 = startIgniteServer("server3");

        //sleep(30_000);
        streamSomeDataWithIndexRange(igniteClient, 1, 2);

        //sleep(30_000);
        System.gc();

        /* Brake point here and check memory
           com.intellij.debugger.memory.ui.JavaTypeInfo@1290	shows 1 CacheContinuousQueryEntry
        */
        closeIgnites(igniteClient, igniteServer1, igniteServer2, igniteServer3);
    }

    @Test
    public void startContinuousQueryWithFilterOnAllNodes_StartNodesAtOnce_LocalQueryTrue() {

        Ignite igniteServer1 = startIgniteServer("server1");
        Ignite igniteServer2 = startIgniteServer("server2");
        Ignite igniteServer3 = startIgniteServer("server3");

        Ignite igniteClient = startIgniteClient();

        //initial cluster
        startContinuousQueryForLocalQueryAndFilterValue(true, true, igniteServer1, igniteServer2, igniteServer3);
        streamSomeDataWithIndexRange(igniteClient, 0, 2);

        //sleep(30_000);
        System.gc();

        /* Brake point here and check memory
           com.intellij.debugger.memory.ui.JavaTypeInfo@1290	shows 1 CacheContinuousQueryEntry
        */
        closeIgnites(igniteClient, igniteServer1, igniteServer2, igniteServer3);
    }

    @Test
    public void startContinuousQueryWithFilterOnAllNodes_StartNodesIncrementally_LocalQueryTrue() {

        Ignite igniteServer1 = startIgniteServer("server1");
        Ignite igniteServer2 = startIgniteServer("server2");

        Ignite igniteClient = startIgniteClient();

        //initial cluster
        startContinuousQueryForLocalQueryAndFilterValue(true, true, igniteServer1, igniteServer2);
        streamSomeDataWithIndexRange(igniteClient, 0, 1);

        Ignite igniteServer3 = startIgniteServer("server3");
        startContinuousQueryForLocalQueryAndFilterValue(true, true, igniteServer3);

        //sleep(30_000);
        streamSomeDataWithIndexRange(igniteClient, 1, 2);

        //sleep(30_000);
        System.gc();

        /* Brake point here and check memory
           com.intellij.debugger.memory.ui.JavaTypeInfo@1290	shows 1 CacheContinuousQueryEntry
        */
        closeIgnites(igniteClient, igniteServer1, igniteServer2, igniteServer3);
    }

    private static void streamSomeDataWithIndexRange(Ignite igniteClient, int i1, int i11) {
        try (IgniteDataStreamer<Integer, LocalDateTime> dataStreamer = igniteClient.dataStreamer(SAMPLE_CACHE_NAME)) {
            dataStreamer.allowOverwrite(true);
            IntStream.range(i1, i11)
                    .forEach(i -> dataStreamer.addData(i, LocalDateTime.now()));
        }
    }

    private static List<ContinuousQuery<Integer, LocalDateTime>> startContinuousQueryForLocalQueryAndFilterValue(boolean localQuery, boolean filterValue, Ignite... ignites) {
        return Stream.of(ignites)
                .map(ignite -> {
                    Factory<CacheEntryEventFilter<Integer, LocalDateTime>> cacheEntryEventFilterFactory = () -> new CacheEntryEventFilter<Integer, LocalDateTime>() {
                        @IgniteInstanceResource
                        Ignite ignite;

                        @Override
                        public boolean evaluate(CacheEntryEvent<? extends Integer, ? extends LocalDateTime> cacheEntryEvent) throws CacheEntryListenerException {
                            LOG.info("Node: " +  ignite.name() + " filter key: " + cacheEntryEvent.getKey());
                            return filterValue;
                        }
                    };

                    ContinuousQuery<Integer, LocalDateTime> continuousQuery = new ContinuousQuery<Integer, LocalDateTime>()
                            .setAutoUnsubscribe(false)
                            .setLocal(localQuery)
                            .setLocalListener(events -> events.forEach(event -> {
                                LOG.info("Node: " +  ignite.name() + " event key: " + event.getKey());
                            }));

                    continuousQuery.setRemoteFilterFactory(cacheEntryEventFilterFactory);

                    getSampleCache(ignite).query(continuousQuery);
                    return continuousQuery;
                }).collect(Collectors.toList());
    }

    private static Ignite startIgniteServer(String instanceName) {
        return Ignition.start(getIgniteBaseConfiguration(instanceName).setClientMode(false));
    }

    private static Ignite startIgniteClient() {
        return Ignition.start(getIgniteBaseConfiguration("client").setClientMode(true));
    }

    private static IgniteConfiguration getIgniteBaseConfiguration(String instanceName) {
        return new IgniteConfiguration()
                .setMetricsLogFrequency(0)
                .setIgniteInstanceName(instanceName)
                .setCacheConfiguration(cacheConfiguration())
                .setDiscoverySpi(discoveryConfiguration());
    }

    //works only for PRIMARY entries
    CacheInterceptor<Integer, LocalDateTime> integerLocalDateTimeCacheInterceptor = new CacheInterceptor<Integer, LocalDateTime>() {
        @IgniteInstanceResource
        Ignite ignite;

        @Nullable
        @Override
        public LocalDateTime onGet(Integer integer, @Nullable LocalDateTime localDateTime) {
            return localDateTime;
        }

        @Nullable
        @Override
        public LocalDateTime onBeforePut(Cache.Entry<Integer, LocalDateTime> entry, LocalDateTime localDateTime) {
            System.out.println("Putting " + ignite.name() + " key " + entry.getKey() + " old val = " + entry.getValue() + " new val = " + localDateTime);
            return localDateTime;
        }

        @Override
        public void onAfterPut(Cache.Entry<Integer, LocalDateTime> entry) {

        }

        @Nullable
        @Override
        public IgniteBiTuple<Boolean, LocalDateTime> onBeforeRemove(Cache.Entry<Integer, LocalDateTime> entry) {
            return null;
        }

        @Override
        public void onAfterRemove(Cache.Entry<Integer, LocalDateTime> entry) {

        }
    };

    @NotNull
    private static CacheConfiguration<Integer, LocalDateTime> cacheConfiguration() {
        return new CacheConfiguration<Integer, LocalDateTime>()
                .setName(SAMPLE_CACHE_NAME)
               // .setInterceptor(integerLocalDateTimeCacheInterceptor)
                .setCacheMode(REPLICATED);
    }

    private static TcpDiscoverySpi discoveryConfiguration() {
        TcpDiscoveryVmIpFinder ipFinder = new TcpDiscoveryVmIpFinder();
        ipFinder.setAddresses(singleton("127.0.0.1:48550..48555"));
        TcpDiscoverySpi tcpDiscoverySpi = new TcpDiscoverySpi();
        tcpDiscoverySpi.setIpFinder(ipFinder);
        tcpDiscoverySpi.setLocalPort(48550);
        tcpDiscoverySpi.setLocalPortRange(5);

        return tcpDiscoverySpi;
    }

    private static IgniteCache<Integer, LocalDateTime> getSampleCache(Ignite ignite) {
        return ignite.cache(SAMPLE_CACHE_NAME);
    }

    private static void closeIgnites(Ignite... ignites) {
        asList(ignites).forEach(IgniteContinuousQueryTest::closeQuietly);
    }

    private static void closeQuietly(Ignite ignite) {
        try {
            ignite.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void sleep(int l) {
        try {
            Thread.sleep(l);
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }
}
