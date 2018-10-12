import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.IgniteDataStreamer;
import org.apache.ignite.Ignition;
import org.apache.ignite.configuration.CacheConfiguration;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.logger.log4j2.Log4J2Logger;
import org.apache.ignite.spi.discovery.tcp.TcpDiscoverySpi;
import org.apache.ignite.spi.discovery.tcp.ipfinder.vm.TcpDiscoveryVmIpFinder;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.stream.IntStream;

import static java.util.Arrays.asList;
import static java.util.Collections.singleton;
import static org.apache.ignite.cache.CacheMode.PARTITIONED;

public class ContinuousQuery {
    private static final String SAMPLE_CACHE_NAME = "TestCache";
    private static final Logger LOG = LoggerFactory.getLogger(ContinuousQuery.class);

    public static void main(String[] args) throws InterruptedException {
        Ignite igniteServer = startIgniteServer();
        Ignite igniteServer2 = startIgniteServer();
        Ignite igniteClient = startIgniteClient();

        new Thread(() -> startContinuousQuery(igniteServer)).run();
        new Thread(() -> startContinuousQuery(igniteServer2)).run();

        new Thread(() -> {
            try (IgniteDataStreamer<Integer, LocalDateTime> dataStreamer = igniteClient.dataStreamer(SAMPLE_CACHE_NAME)) {
                dataStreamer.allowOverwrite(true);
                IntStream.range(0, 2)
                        .forEach(i -> dataStreamer.addData(i, LocalDateTime.now()));
            }
        }).run();

        new Thread(() -> {
            try (IgniteDataStreamer<Integer, LocalDateTime> dataStreamer = igniteClient.dataStreamer(SAMPLE_CACHE_NAME)) {
                dataStreamer.allowOverwrite(true);
                IntStream.range(0, 4)
                        .forEach(i -> dataStreamer.addData(i, LocalDateTime.now()));

                IntStream.range(3, 4)
                        .forEach(dataStreamer::removeData);
            }
        }).run();

        Thread.sleep(5000);

        closeIgnites(igniteServer, igniteServer2, igniteClient);
    }

    @NotNull
    private static org.apache.ignite.cache.query.ContinuousQuery<Integer, LocalDateTime> startContinuousQuery(Ignite ignite) {
        org.apache.ignite.cache.query.ContinuousQuery<Integer, LocalDateTime> continuousQuery = new org.apache.ignite.cache.query.ContinuousQuery<>();
        continuousQuery.setLocalListener(events ->
                events.forEach(event -> {
                    LOG.info("IgniteNode: {} Event type={}  key={} oldVal={} newVal={}", ignite.cluster().localNode().id(), event.getEventType(), event.getKey(), event.getOldValue(), event.getValue());
                }));

        getSampleCache(ignite).query(continuousQuery);
        return continuousQuery;
    }

    private static Ignite startIgniteServer() {
        return Ignition.start(getIgniteBaseConfiguration().setClientMode(false));
    }

    private static Ignite startIgniteClient() {
        return Ignition.start(getIgniteBaseConfiguration().setClientMode(true));
    }

    private static IgniteConfiguration getIgniteBaseConfiguration() {
        return new IgniteConfiguration()
                .setMetricsLogFrequency(0)
                .setGridLogger(getIgniteLogger())
                .setIgniteInstanceName(UUID.randomUUID().toString())
                .setCacheConfiguration(cacheConfiguration())
                .setDiscoverySpi(discoveryConfiguration());
    }

    @NotNull
    private static Log4J2Logger getIgniteLogger() {
        try {
            return new Log4J2Logger(new ClassPathResource("log4j2.xml").getFile());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @NotNull
    private static CacheConfiguration<String, LocalDateTime> cacheConfiguration() {
        return new CacheConfiguration<String, LocalDateTime>()
                .setName(SAMPLE_CACHE_NAME)
                .setCacheMode(PARTITIONED)
                .setBackups(1);
    }

    private static TcpDiscoverySpi discoveryConfiguration() {
        TcpDiscoveryVmIpFinder ipFinder = new TcpDiscoveryVmIpFinder();
        ipFinder.setAddresses(singleton("127.0.0.1:48550..48552"));
        TcpDiscoverySpi tcpDiscoverySpi = new TcpDiscoverySpi();
        tcpDiscoverySpi.setIpFinder(ipFinder);
        tcpDiscoverySpi.setLocalPort(48550);
        tcpDiscoverySpi.setLocalPortRange(2);

        return tcpDiscoverySpi;
    }

    private static IgniteCache<Integer, LocalDateTime> getSampleCache(Ignite ignite) {
        return ignite.cache(SAMPLE_CACHE_NAME);
    }

    private static void closeIgnites(Ignite... ignites) {
        asList(ignites).forEach(ignite -> {
            closeQuietly(ignite);
        });
    }

    private static void closeQuietly(Ignite ignite) {
        try {
            ignite.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


}
