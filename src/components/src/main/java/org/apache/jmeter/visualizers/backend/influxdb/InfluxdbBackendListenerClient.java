/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.jmeter.visualizers.backend.influxdb;

import static org.apache.jmeter.visualizers.backend.influxdb.entity.Constants.METRICS_EVENTS_ENDED;
import static org.apache.jmeter.visualizers.backend.influxdb.entity.Constants.METRICS_EVENTS_STARTED;

import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.shulie.constants.PressureConstants;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jmeter.visualizers.backend.*;
import org.apache.jmeter.visualizers.backend.influxdb.entity.EventMetrics;
import org.apache.jmeter.visualizers.backend.influxdb.entity.ResponseMetrics;
import org.apache.jmeter.visualizers.backend.influxdb.entity.ResponseMetrics.ErrorInfo;
import org.apache.jmeter.visualizers.backend.influxdb.tro.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of {@link AbstractBackendListenerClient} to write in an InfluxDB using
 * custom schema
 *
 * @since 3.2
 */
public class InfluxdbBackendListenerClient extends AbstractBackendListenerClient implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(InfluxdbBackendListenerClient.class);
    private static final String TAG_OK = "ok";
    private static final String TAG_KO = "ko";
    private static final String TAG_ALL = "all";
    private static final String CUMULATED_METRICS = "all";
    private static final long SEND_INTERVAL = JMeterUtils.getPropDefault("backend_influxdb.send_interval", 5);
    private static final int MAX_POOL_SIZE = 1;
    private static final String SEPARATOR = ";"; //$NON-NLS-1$
    private static final Object LOCK = new Object();
    private static final Map<String, String> DEFAULT_ARGS = new LinkedHashMap<>();

    static {
        DEFAULT_ARGS.put("influxdbMetricsSender", HttpJsonMetricsSender.class.getName());
        DEFAULT_ARGS.put("influxdbUrl", "http://host_to_change:8086/write?db=jmeter");
        DEFAULT_ARGS.put("application", "application name");
        DEFAULT_ARGS.put("measurement", "jmeter");
        DEFAULT_ARGS.put("summaryOnly", "false");
        DEFAULT_ARGS.put("samplersRegex", ".*");
        DEFAULT_ARGS.put("percentiles", "99;95;90");
        DEFAULT_ARGS.put("testTitle", "Test name");
        DEFAULT_ARGS.put("eventTags", "");
    }

    private final ConcurrentHashMap<String, SamplerMetric> metricsPerSampler = new ConcurrentHashMap<>();
    private boolean summaryOnly;
    private String samplersRegex = "";
    private Pattern samplersToFilter;
    private Map<String, Float> okPercentiles;
    private Map<String, Float> koPercentiles;
    private Map<String, Float> allPercentiles;

    private InfluxdbMetricsSender influxdbMetricsManager;

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> timerHandle;

    private Map<String, String> bizMap = new HashMap<>();

    public InfluxdbBackendListenerClient() {
        super();
    }

    //使用newScheduledThreadPool线程池 每5S执行一次
    @Override
    public void run() {
        sendMetrics();
    }

    private void sendMetrics() {
        synchronized (LOCK) {
            for (Map.Entry<String, SamplerMetric> entry : metricsPerSampler.entrySet()) {
                SamplerMetric metric = entry.getValue();
                if (entry.getKey().equals(CUMULATED_METRICS)) {
                    addCumulatedMetrics(metric);
                } else {
                    addMetrics(AbstractInfluxdbMetricsSender.tagToStringValue(entry.getKey()), metric);
                }
                // We are computing on interval basis so cleanup
                metric.resetForTimeInterval();
            }
        }

        influxdbMetricsManager.writeAndSendMetrics();
    }

    private void addMetrics(String transaction, SamplerMetric metric) {
        // ALL
        addResponseMetric(transaction, metric.getTransactionUrl(), metric.getTotal(), metric.getFailures(), metric.getSentBytes(),
            metric.getReceivedBytes(),
            TAG_ALL, metric.getAllMean(),
            metric.getAllMinTime(),
            metric.getAllMaxTime(),
            allPercentiles.values(), metric::getAllPercentile,
            metric.getSaSuccess(),
            metric.getSumRt(),
            metric.getErrors());
    }

    private void addResponseMetric(String transaction, String transactionUrl, int count, int countError,
        Long sentBytes, Long receivedBytes,
        String statut, double rt, double minTime, double maxTime,
        Collection<Float> pcts, PercentileProvider percentileProvider, int saCount,
        long sumRt,
        Map<ErrorMetric, Integer> errors) {
        ResponseMetrics responseMetrics = new ResponseMetrics();
        responseMetrics.setTransaction(transaction);
        responseMetrics.setCount(count);
        responseMetrics.setFailCount(countError);
        responseMetrics.setMaxRt(maxTime);
        responseMetrics.setMinRt(minTime);
        responseMetrics.setTimestamp(System.currentTimeMillis());
        responseMetrics.setRt(rt);
        responseMetrics.setSaCount(saCount);
        String podNumber = System.getProperty("pod.number");
        Map<String, String> tags = new HashMap<>();
        tags.put("podNum", podNumber == null ? "" : podNumber);
        responseMetrics.setTags(tags);
        responseMetrics.setSentBytes(sentBytes);
        responseMetrics.setReceivedBytes(receivedBytes);
        //add by lipeng 添加活跃线程数
        responseMetrics.setActiveThreads(JMeterContextService.getThreadCounts().activeThreads);
        //添加 transactionurl
        responseMetrics.setTransactionUrl(transactionUrl);
        //add end
//        Set<ErrorInfo> errorInfos = errors.keySet()
//            .stream()
//            .map(integer -> {
//                ErrorInfo errorInfo = new ErrorInfo();
//                errorInfo.setResponseCode(integer.getResponseCode());
//                errorInfo.setResponseMessage(integer.getResponseMessage());
//                return errorInfo;
//            }).collect(Collectors.toSet());
        //modify by lipeng 错误信息走jtl 不走metrics， 防止错误信息太大导致请求不稳定
        responseMetrics.setErrorInfos(new HashSet<>());
        //add by lipeng 添加sumRt
        responseMetrics.setSumRt(sumRt);
        influxdbMetricsManager.addMetric(responseMetrics);
    }

    private void addCumulatedMetrics(SamplerMetric metric) {
        ResponseMetrics responseMetrics = new ResponseMetrics();
        responseMetrics.setTransaction(CUMULATED_METRICS);
        //add by lipeng  默认所有的为all
        responseMetrics.setTransactionUrl(CUMULATED_METRICS);
        responseMetrics.setCount(metric.getTotal());
        responseMetrics.setFailCount(metric.getFailures());
        responseMetrics.setMaxRt(metric.getAllMaxTime());
        responseMetrics.setMinRt(metric.getAllMinTime());
        responseMetrics.setTimestamp(System.currentTimeMillis());
        responseMetrics.setRt(metric.getAllMean());
        //modify by lipeng 当transcation为all时返回的saCount均设置为0，因为all的sa count为空，让cloud去聚合all的sacount数据
        // 平台会设置每个业务活动的目标rt，而不会给all设置目标rt，设置目标rt根据脚本后端监听器中的businessMap参数传递过来
//        responseMetrics.setSaCount(metric.getSaSuccess());
        responseMetrics.setSaCount(0);
        //modify end
        String podNumber = System.getProperty("pod.number");
        Map<String, String> tags = new HashMap<>();
        tags.put("podNum", podNumber == null ? "" : podNumber);
        responseMetrics.setTags(tags);
        responseMetrics.setSentBytes(metric.getSentBytes());
        responseMetrics.setReceivedBytes(metric.getReceivedBytes());
        //add by lipeng 添加活跃线程数
        responseMetrics.setActiveThreads(JMeterContextService.getThreadCounts().activeThreads);
        //add end
        //add by lipeng 添加sumRt
        responseMetrics.setSumRt(metric.getSumRt());
        influxdbMetricsManager.addMetric(responseMetrics);
    }

    public String getSamplersRegex() {
        return samplersRegex;
    }

    /**
     * @param samplersList the samplersList to set
     */
    public void setSamplersList(String samplersList) {
        this.samplersRegex = samplersList;
    }

    @Override
    public void handleSampleResults(List<SampleResult> sampleResults, BackendListenerContext context) {
        synchronized (LOCK) {
            UserMetric userMetrics = getUserMetrics();
            for (SampleResult sampleResult : sampleResults) {
                userMetrics.add(sampleResult);
                Matcher matcher = samplersToFilter.matcher(sampleResult.getSampleLabel());
                if (!summaryOnly && (matcher.find())) {
                    SamplerMetric samplerMetric = getSamplerMetricInfluxdb(sampleResult.getSampleLabel()
                            , sampleResult.getTransactionUrl());
                    samplerMetric.add(sampleResult);
                }
                //TODO optimize sf add switch
                SamplerMetric cumulatedMetrics = getSamplerMetricInfluxdb(CUMULATED_METRICS
                        , sampleResult.getTransactionUrl());
                cumulatedMetrics.addCumulated(sampleResult);
            }
        }
    }

    @Override
    public void setupTest(BackendListenerContext context) throws Exception {
        summaryOnly = context.getBooleanParameter("summaryOnly", false);
        samplersRegex = context.getParameter("samplersRegex", "");
        String bizArgs = context.getParameter("businessMap", "");
        if (StringUtils.isNotBlank(bizArgs)) {
            bizMap = JsonUtil.parse(bizArgs, Map.class);
        }

        initPercentiles(context);
        initInfluxdbMetricsManager(context);

        samplersToFilter = Pattern.compile(samplersRegex);
        addAnnotation(true);

        scheduler = Executors.newScheduledThreadPool(MAX_POOL_SIZE);
        // Start immediately the scheduler and put the pooling ( 5 seconds by default )
        this.timerHandle = scheduler.scheduleAtFixedRate(this, 0, SEND_INTERVAL, TimeUnit.SECONDS);
        //测试 每500ms获取一次数据
//        this.timerHandle = scheduler.scheduleAtFixedRate(this, 0, 500, TimeUnit.MILLISECONDS);
    }

    private void initInfluxdbMetricsManager(BackendListenerContext context) throws Exception {
        Class<?> troCloudClazz = Class.forName(context.getParameter("influxdbMetricsSender").trim());
        // 获得实例
        influxdbMetricsManager = (HttpJsonMetricsSender)troCloudClazz.getDeclaredConstructor().newInstance();
        String influxdbUrl = context.getParameter("influxdbUrl").trim();
        String influxdbToken = context.getParameter("influxdbToken");
        influxdbMetricsManager.setup(influxdbUrl, influxdbToken);
    }

    private void initPercentiles(BackendListenerContext context) {
        String percentilesAsString = context.getParameter("percentiles", "");
        String[] percentilesStringArray = percentilesAsString.split(SEPARATOR);
        okPercentiles = new HashMap<>(percentilesStringArray.length);
        koPercentiles = new HashMap<>(percentilesStringArray.length);
        allPercentiles = new HashMap<>(percentilesStringArray.length);
        DecimalFormat format = new DecimalFormat("0.##");
        for (String percentile : percentilesStringArray) {
            String trimmedPercentile = percentile.trim();
            if (StringUtils.isEmpty(trimmedPercentile)) {
                continue;
            }
            try {
                Float percentileValue = Float.valueOf(trimmedPercentile);
                String key = AbstractInfluxdbMetricsSender.tagToStringValue(format.format(percentileValue));
                okPercentiles.put(key, percentileValue);
                koPercentiles.put(key, percentileValue);
                allPercentiles.put(key, percentileValue);
            } catch (Exception e) {
                log.error("Error parsing percentile: '{}'", percentile, e);
            }
        }
    }

    private SamplerMetric getSamplerMetricInfluxdb(String sampleLabel, String transactionUrl) {
        SamplerMetric samplerMetric = metricsPerSampler.get(sampleLabel);
        if (samplerMetric != null) {
            return samplerMetric;
        }
        SamplerMetric newSamplerMetric = new SamplerMetric();
        //add by lipeng  添加业务活动url
        newSamplerMetric.setTransactionUrl(transactionUrl);
        newSamplerMetric.setStandRt(Integer.parseInt(bizMap.getOrDefault(getMetricLabel(sampleLabel), "0")));
        SamplerMetric oldValue = metricsPerSampler.putIfAbsent(sampleLabel, newSamplerMetric);
        if (oldValue != null) {
            newSamplerMetric = oldValue;
        }
        return newSamplerMetric;
    }

    public String getMetricLabel(String sampleLabel) {
        return sampleLabel + "_rt";
    }

    @Override
    public void teardownTest(BackendListenerContext context) throws Exception {
        boolean cancelState = timerHandle.cancel(false);
        log.debug("Canceled state: {}", cancelState);
        scheduler.shutdown();
        try {
            scheduler.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            log.error("Error waiting for end of scheduler");
            Thread.currentThread().interrupt();
        }

        addAnnotation(false);

        // Send last set of data before ending
        log.info("Sending last metrics");
        sendMetrics();
        influxdbMetricsManager.destroy();
        super.teardownTest(context);
    }

    /**
     *
     */
    private void addAnnotation(boolean isStartOfTest) {
        EventMetrics eventMetrics = new EventMetrics();
        eventMetrics.setEventName(isStartOfTest ? METRICS_EVENTS_STARTED : METRICS_EVENTS_ENDED);
        //add by lipeng tags添加当前jtl文件名
        Map<String, String> tags = new HashMap<>();
        tags.put(PressureConstants.CURRENT_JTL_FILE_NAME_SYSTEM_PROP_KEY
                , System.getProperty(PressureConstants.CURRENT_JTL_FILE_NAME_SYSTEM_PROP_KEY));
        eventMetrics.setTags(tags);
        eventMetrics.setTimestamp(System.currentTimeMillis());
        influxdbMetricsManager.addEventMetrics(eventMetrics);
    }

    @Override
    public Arguments getDefaultParameters() {
        Arguments arguments = new Arguments();
        DEFAULT_ARGS.forEach(arguments::addArgument);
        return arguments;
    }

    @FunctionalInterface
    private interface PercentileProvider {
        double getPercentileValue(double percentile);
    }

}
