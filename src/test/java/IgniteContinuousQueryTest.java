import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.IgniteDataStreamer;
import org.apache.ignite.Ignition;
import org.apache.ignite.cache.query.ContinuousQuery;
import org.apache.ignite.configuration.CacheConfiguration;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.logger.log4j2.Log4J2Logger;
import org.apache.ignite.spi.discovery.tcp.TcpDiscoverySpi;
import org.apache.ignite.spi.discovery.tcp.ipfinder.vm.TcpDiscoveryVmIpFinder;
import org.jetbrains.annotations.NotNull;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static java.util.Arrays.asList;
import static java.util.Collections.singleton;
import static java.util.concurrent.CompletableFuture.runAsync;
import static java.util.concurrent.CompletableFuture.supplyAsync;
import static org.apache.ignite.cache.CacheMode.REPLICATED;

/**
 * Demonstrate different continuous query local listener behavior depending on query .setLocal, and different query deployment type.
 */
public class IgniteContinuousQueryTest {
    private static final Logger LOG = LoggerFactory.getLogger(IgniteContinuousQueryTest.class);

    private static final String SAMPLE_CACHE_NAME = "TestCache";

    @Test
    public void startContinuousQueryWithFilterOnAllNodes_StartNodesIncrementally_LocalQueryTrue() throws ExecutionException, InterruptedException {

        Ignite server1 = supplyAsync(() -> startIgniteServerWithLocalQuery("server1")).get();
        Ignite igniteClient = startIgniteClient();

        //initial cluster
        int batchSize = 100_000;

        sleep(2_000);
        CompletableFuture<Void> dataPopulation1 = runAsync(() -> streamSomeDataWithIndexRange(igniteClient, 0, batchSize));

        Ignite server3 = supplyAsync(() -> startIgniteServerWithLocalQuery("server3")).get();
        Ignite server2 = supplyAsync(() -> startIgniteServerWithLocalQuery("server2")).get();

        dataPopulation1.join();

        CompletableFuture<Void> dataPopulation2 = runAsync(
                () -> streamSomeDataWithIndexRange(igniteClient, batchSize, 2 * batchSize));

        dataPopulation2.join();

        sleep(60_000);

        for (int i = 0; i < 10; i++) {
            sleep(2_000);
            System.gc();
        }

        closeIgnites(igniteClient, server1, server2, server3);
    }

    private Ignite startIgniteServerWithLocalQuery(String server1) {
        Ignite igniteServer1 = startIgniteServer(server1);
        startContinuousQueryForLocalQuery(igniteServer1);
        return igniteServer1;
    }

    private static void streamSomeDataWithIndexRange(Ignite igniteClient, int i1, int i11) {
        LOG.info("Data population started for range {}-{}", i1, i11);
        try (IgniteDataStreamer<Integer, LocalDateTime> dataStreamer = igniteClient.dataStreamer(SAMPLE_CACHE_NAME)) {
            dataStreamer.allowOverwrite(true);
            IntStream.range(i1, i11)
                    .forEach(i -> dataStreamer.addData(i, LocalDateTime.now()));
        }
        LOG.info("Data population finished for range {}-{}", i1, i11);
    }

    private static List<ContinuousQuery<Integer, LocalDateTime>> startContinuousQueryForLocalQuery(Ignite... ignites) {
        return Stream.of(ignites)
                .map(ignite -> {
                       ContinuousQuery<Integer, LocalDateTime> continuousQuery = new ContinuousQuery<Integer, LocalDateTime>()
                            .setLocal(true)
                            .setLocalListener(events -> events.forEach(event -> {
                                LOG.trace("Node: [{}] LocalListener key: [{}]", ignite.name(), event.getKey());
                            }));

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
                .setGridLogger(getIgniteLogger())
                .setIgniteInstanceName(instanceName)
                .setCacheConfiguration(cacheConfiguration())
                .setDiscoverySpi(discoveryConfiguration());
    }

    @NotNull
    private static CacheConfiguration<Integer, LocalDateTime> cacheConfiguration() {
        return new CacheConfiguration<Integer, LocalDateTime>()
                .setName(SAMPLE_CACHE_NAME)
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

    @NotNull
    private static Log4J2Logger getIgniteLogger() {
        try {
            return new Log4J2Logger(new ClassPathResource("log4j2.xml").getFile());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
