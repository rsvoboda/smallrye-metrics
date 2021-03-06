/*
 * Copyright 2017 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package io.smallrye.metrics;

import io.smallrye.metrics.app.ConcurrentGaugeImpl;
import io.smallrye.metrics.app.CounterImpl;
import io.smallrye.metrics.app.ExponentiallyDecayingReservoir;
import io.smallrye.metrics.app.HistogramImpl;
import io.smallrye.metrics.app.MeterImpl;
import io.smallrye.metrics.app.TimerImpl;

import java.util.SortedSet;
import java.util.TreeSet;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.metrics.ConcurrentGauge;
import org.eclipse.microprofile.metrics.Counter;
import org.eclipse.microprofile.metrics.Gauge;
import org.eclipse.microprofile.metrics.Histogram;
import org.eclipse.microprofile.metrics.Metadata;
import org.eclipse.microprofile.metrics.MetadataBuilder;
import org.eclipse.microprofile.metrics.Meter;
import org.eclipse.microprofile.metrics.Metric;
import org.eclipse.microprofile.metrics.MetricFilter;
import org.eclipse.microprofile.metrics.MetricID;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.MetricType;
import org.eclipse.microprofile.metrics.Timer;
import org.jboss.logging.Logger;

import javax.enterprise.inject.Vetoed;
import javax.enterprise.inject.spi.InjectionPoint;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author hrupp
 */
@Vetoed
public class MetricsRegistryImpl extends MetricRegistry {

    private static final Logger log = Logger.getLogger(MetricsRegistryImpl.class);
    private static final String NONE = "NONE";

    private Map<String, Metadata> metadataMap = new HashMap<>();
    private Map<MetricID, Metric> metricMap = new ConcurrentHashMap<>();

    Optional<String> globalTags;

    public MetricsRegistryImpl() {

        Config c  = org.eclipse.microprofile.config.ConfigProvider.getConfig();

        // TODO find out if this dance is still needed in newer versions of config
        globalTags = c.getOptionalValue("mp.metrics.tags", String.class);
        if (globalTags.isPresent()) {
            return;
        }
        globalTags = c.getOptionalValue("MP_METRICS_TAGS",String.class);

    }

    @Override
    public <T extends Metric> T register(String name, T metric) throws IllegalArgumentException {

        if (metricMap.keySet().contains(name)) {
            throw new IllegalArgumentException("A metric with name " + name + " already exists");
        }

        MetricType type;
        Class<?> metricCls = metric.getClass();
        if (metricCls.getName().contains("Lambda")) {
            String tname = metricCls.getGenericInterfaces()[0].getTypeName(); // TODO [0] is brittle
            tname = tname.substring(tname.lastIndexOf('.') + 1);
            tname = tname.toLowerCase();
            type = MetricType.from(tname);
        } else if (metricCls.isAnonymousClass()) {
            type = MetricType.from(metricCls.getInterfaces().length == 0 ? metricCls.getSuperclass().getInterfaces()[0] : metricCls.getInterfaces()[0]);
        } else {
            if (!metricCls.isInterface()) {
                // [0] is ok, as all our Impl classes implement exactly the one matching interface
                type = MetricType.from(metricCls.getInterfaces()[0]);
            } else {
                type = MetricType.from(metricCls);
            }
        }

        MetadataBuilder  mb = Metadata.builder().withName(name).withType(type);
        if (globalTags.isPresent()) {
            addGlobalTags(mb);
        }
        Metadata m = mb.build();
        metricMap.put(new MetricID(name), metric);

        metadataMap.put(name, m);
        return metric;
    }

    private void addGlobalTags(MetadataBuilder mb) {
        if (!globalTags.isPresent()) {
            return;
        }

        String[] tagsArray=globalTags.get().split(",");
        for (String aTag : tagsArray) {
            // mb.addTag(aTag); TODO
        }
    }

    @Override
    public <T extends Metric> T register(Metadata metadata, T metric) throws IllegalArgumentException {

        String name = metadata.getName();
        if (name == null) {
            throw new IllegalArgumentException("Metric name must not be null");
        }
        Metadata existingMetadata = metadataMap.get(name);

        boolean reusableFlag = (existingMetadata == null || existingMetadata.isReusable());

        //Gauges are not reusable
        if (metadata.getTypeRaw().equals(MetricType.GAUGE)) {
            reusableFlag = false;
        }

        if (metricMap.keySet().contains(metadata.getName()) && !reusableFlag) {
            throw new IllegalArgumentException("A metric with name " + metadata.getName() + " already exists");
        }

        if (existingMetadata != null && !existingMetadata.getTypeRaw().equals(metadata.getTypeRaw())) {
            throw new IllegalArgumentException("Passed metric type does not match existing type");
        }

        if (existingMetadata != null && (existingMetadata.isReusable() != metadata.isReusable())) {
            throw new IllegalArgumentException("Reusable flag differs from previous usage");
        }

        metricMap.put(new MetricID(name), metric);
        metadataMap.put(name, duplicate(metadata));

        return metric;
    }

    @Override
    public <T extends Metric> T register(Metadata metadata, T metric, org.eclipse.microprofile.metrics.Tag... tags) throws IllegalArgumentException {
        return null;  // TODO: Customise this generated block
    }

    protected Metadata duplicate(Metadata meta) {
        Metadata copy = null;
        Map<String, String> tagsCopy = new HashMap<>();
//        tagsCopy.putAll(meta.getTags());  // TODO
        if (globalTags.isPresent()) {
            String[] tagsArray = globalTags.get().split(",");
            for (String aTag : tagsArray) {
                Tag t = new Tag(aTag);
                tagsCopy.put(t.getKey(),t.getValue());
            }
        }


        if (meta instanceof OriginTrackedMetadata) {
            copy = new OriginTrackedMetadata(((OriginTrackedMetadata) meta).getOrigin(), meta.getName(),
                                             meta.getTypeRaw(), meta.getUnit().orElse(NONE), meta.getDescription().orElse(""),
                                             meta.getDisplayName(),
                                             meta.isReusable(),
                                             tagsCopy);
        } else {
            MetadataBuilder builder = Metadata.builder().withName(meta.getName()).withType(meta.getTypeRaw());

            builder.withDisplayName(meta.getDisplayName());
            if (meta.isReusable()) {
                builder.reusable();
            } else {
                builder.notReusable();
            }
            builder.withUnit(meta.getUnit().orElse(NONE));
            builder.withDescription(meta.getDescription().orElse(""));

            for (Map.Entry<String,String> tag : tagsCopy.entrySet()) {
                // builder.addTag(tag.getKey()+"="+tag.getValue());  TODO
            }
            addGlobalTags(builder);
            copy = builder.build();
        }

        return copy;
    }

    @Override
    public Counter counter(String name) {
        return counter(Metadata.builder().withName(name).withType(MetricType.COUNTER).build());
    }

    @Override
    public Counter counter(String name, org.eclipse.microprofile.metrics.Tag... tags) {
        return null;  // TODO: Customise this generated block
    }

    @Override
    public Counter counter(Metadata metadata, org.eclipse.microprofile.metrics.Tag... tags) {
        return null;  // TODO: Customise this generated block
    }

    @Override
    public org.eclipse.microprofile.metrics.Counter counter(Metadata metadata) {
        return get(metadata, MetricType.COUNTER);
    }

    @Override
    public ConcurrentGauge concurrentGauge(String name) {
        return concurrentGauge(Metadata.builder().withName(name).withType(MetricType.CONCURRENT_GAUGE).build());
    }

    @Override
    public ConcurrentGauge concurrentGauge(Metadata metadata) {
        return get(metadata, MetricType.CONCURRENT_GAUGE);
    }

    @Override
    public ConcurrentGauge concurrentGauge(String name, org.eclipse.microprofile.metrics.Tag... tags) {
        return null;  // TODO: Customise this generated block
    }

    @Override
    public ConcurrentGauge concurrentGauge(Metadata metadata, org.eclipse.microprofile.metrics.Tag... tags) {
        return null;  // TODO: Customise this generated block
    }

    @Override
    public Histogram histogram(String name) {
        return histogram(Metadata.builder().withName(name).withType(MetricType.HISTOGRAM).build());
    }

    @Override
    public Histogram histogram(Metadata metadata) {
        return get(metadata, MetricType.HISTOGRAM);
    }

    @Override
    public Histogram histogram(String name, org.eclipse.microprofile.metrics.Tag... tags) {
        return null;  // TODO: Customise this generated block
    }

    @Override
    public Histogram histogram(Metadata metadata, org.eclipse.microprofile.metrics.Tag... tags) {
        return null;  // TODO: Customise this generated block
    }

    @Override
    public Meter meter(String s) {
        return meter(Metadata.builder().withName(s).withType(MetricType.METERED).build());
    }

    @Override
    public Meter meter(Metadata metadata) {
        return get(metadata, MetricType.METERED);
    }

    @Override
    public Meter meter(String name, org.eclipse.microprofile.metrics.Tag... tags) {
        return null;  // TODO: Customise this generated block
    }

    @Override
    public Meter meter(Metadata metadata, org.eclipse.microprofile.metrics.Tag... tags) {
        return null;  // TODO: Customise this generated block
    }

    private <T extends Metric> T get(Metadata metadata, MetricType type) {
        String name = metadata.getName();
        log.debugf("Get metric [name: %s, type: %s]", name, type);
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Name must not be null or empty");
        }

        Metadata previous = metadataMap.get(name);

        if (previous == null) {
            Metric m;
            switch (type) {

                case COUNTER:
                    m = new CounterImpl();
                    break;
                case GAUGE:
                    throw new IllegalArgumentException("Gauge " + name + " was not registered, this should not happen");
                case METERED:
                    m = new MeterImpl();
                    break;
                case HISTOGRAM:
                    m = new HistogramImpl(new ExponentiallyDecayingReservoir());
                    break;
                case TIMER:
                    m = new TimerImpl(new ExponentiallyDecayingReservoir());
                    break;
                case CONCURRENT_GAUGE:
                    m = new ConcurrentGaugeImpl();
                    break;
                case INVALID:
                default:
                    throw new IllegalStateException("Must not happen");
            }
            log.infof("Register metric [name: %s, type: %s]", name, type);
            register(metadata, m);
        } else if (!previous.getTypeRaw().equals(metadata.getTypeRaw())) {
            throw new IllegalArgumentException("Previously registered metric " + name + " is of type "
                    + previous.getType() + ", expected " + metadata.getType());
        } else if ( haveCompatibleOrigins(previous, metadata)) {
            // stop caring, same thing.
        } else if (previous.isReusable() && !metadata.isReusable()) {
            throw new IllegalArgumentException("Previously registered metric " + name + " was flagged as reusable, while current request is not.");
        } else if (!previous.isReusable()) {
            throw new IllegalArgumentException("Previously registered metric " + name + " was not flagged as reusable");
        }

        return (T) metricMap.get(name);
    }

    private boolean haveCompatibleOrigins(Metadata left, Metadata right) {
        if ( left instanceof OriginTrackedMetadata && right instanceof OriginTrackedMetadata ) {
            OriginTrackedMetadata leftOrigin = (OriginTrackedMetadata) left;
            OriginTrackedMetadata rightOrigin = (OriginTrackedMetadata) right;

            if ( leftOrigin.getOrigin().equals(rightOrigin.getOrigin())) {
                return true;
            }

            if ( leftOrigin.getOrigin() instanceof InjectionPoint || ((OriginTrackedMetadata) right).getOrigin() instanceof InjectionPoint ) {
                return true;
            }

        }

        return false;

    }

    @Override
    public Timer timer(String s) {
        return timer(Metadata.builder().withName(s).withType(MetricType.TIMER).build());
    }

    @Override
    public Timer timer(Metadata metadata) {
        return get(metadata, MetricType.TIMER);
    }

    @Override
    public Timer timer(String name, org.eclipse.microprofile.metrics.Tag... tags) {
        return null;  // TODO: Customise this generated block
    }

    @Override
    public Timer timer(Metadata metadata, org.eclipse.microprofile.metrics.Tag... tags) {
        return null;  // TODO: Customise this generated block
    }

    @Override
    public boolean remove(String metricName) {
        MetricID idToRemove = new MetricID(metricName);
        if (metricMap.containsKey(idToRemove)) {
            log.debugf("Remove metric [name: %s]", metricName);
            metricMap.remove(idToRemove);
            metadataMap.remove(metricName);
            return true;
        }
        return false;
    }

    @Override
    public boolean remove(MetricID metricID) {
        if (metricMap.containsKey(metricID)) {
            log.debugf("Remove metric [id: %s]", metricID);
            metricMap.remove(metricID);
            metadataMap.remove(metricID.getName());
            return true;
        }
        return false;
    }

    @Override
    public void removeMatching(MetricFilter metricFilter) {
        Iterator<Map.Entry<MetricID, Metric>> iterator = metricMap.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<MetricID, Metric> entry = iterator.next();
            if (metricFilter.matches(entry.getKey(), entry.getValue())) {
                remove(entry.getKey());
            }
        }
    }

    @Override
    public java.util.SortedSet<String> getNames() {
        SortedSet<String> out = new TreeSet<>();
        for (MetricID id : metricMap.keySet()) {
            out.add(id.getName());
        }
        return out;
    }

    @Override
    public SortedSet<MetricID> getMetricIDs() {
        return new TreeSet<>(metricMap.keySet());
    }

    @Override
    public SortedMap<MetricID, Gauge> getGauges() {
        return getGauges(MetricFilter.ALL);
    }

    @Override
    public SortedMap<MetricID, Gauge> getGauges(MetricFilter metricFilter) {
        return getMetrics(MetricType.GAUGE, metricFilter);
    }

    @Override
    public SortedMap<MetricID, Counter> getCounters() {
        return getCounters(MetricFilter.ALL);
    }

    @Override
    public SortedMap<MetricID, Counter> getCounters(MetricFilter metricFilter) {
        return getMetrics(MetricType.COUNTER, metricFilter);
    }

    @Override
    public SortedMap<MetricID, ConcurrentGauge> getConcurrentGauges() {
        return getConcurrentGauges(MetricFilter.ALL);
    }

    @Override
    public SortedMap<MetricID, ConcurrentGauge> getConcurrentGauges(MetricFilter metricFilter) {
        return getMetrics(MetricType.CONCURRENT_GAUGE, metricFilter);
    }

    @Override
    public java.util.SortedMap<MetricID, Histogram> getHistograms() {
        return getHistograms(MetricFilter.ALL);
    }

    @Override
    public java.util.SortedMap<MetricID, Histogram> getHistograms(MetricFilter metricFilter) {
        return getMetrics(MetricType.HISTOGRAM, metricFilter);
    }

    @Override
    public java.util.SortedMap<MetricID, Meter> getMeters() {
        return getMeters(MetricFilter.ALL);
    }

    @Override
    public java.util.SortedMap<MetricID, Meter> getMeters(MetricFilter metricFilter) {
        return getMetrics(MetricType.METERED, metricFilter);
    }

    @Override
    public java.util.SortedMap<MetricID, Timer> getTimers() {
        return getTimers(MetricFilter.ALL);
    }

    @Override
    public java.util.SortedMap<MetricID, Timer> getTimers(MetricFilter metricFilter) {
        return getMetrics(MetricType.TIMER, metricFilter);
    }

    @Override
    public Map<MetricID, Metric> getMetrics() {

        return new HashMap<>(metricMap);
    }

    private <T extends Metric> SortedMap<MetricID, T> getMetrics(MetricType type, MetricFilter filter) {
        SortedMap<MetricID, T> out = new TreeMap<>();

        for (Map.Entry<MetricID, Metric> entry : metricMap.entrySet()) {
            Metadata metadata = metadataMap.get(entry.getKey().getName());
            if (metadata.getTypeRaw() == type) {
                if (filter.matches(entry.getKey(), entry.getValue())) {
                    out.put(entry.getKey(), (T) entry.getValue());
                }
            }
        }

        return out;
    }

    @Override
    public Map<String, Metadata> getMetadata() {
        return new HashMap<>(metadataMap);
    }
}
