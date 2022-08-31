/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.common.metrics.stats;

import java.util.ArrayList;
import java.util.List;

import org.apache.kafka.common.metrics.MeasurableStat;
import org.apache.kafka.common.metrics.MetricConfig;

/**
 * A SampledStat records a single scalar value measured over one or more samples. Each sample is recorded over a
 * configurable window. The window can be defined by number of events or elapsed time (or both, if both are given the
 * window is complete when <i>either</i> the event count or elapsed time criterion is met).
 * SampledStat记录基于窗口中的一个或多个sample（样本）计算出的统计值（状态）
 * 窗口可以通过样本的事件数量和过期时间定义过期策略
 * <p>
 * All the samples are combined to produce the measurement. When a window is complete the oldest sample is cleared and
 * recycled to begin recording the next sample.
 * 当窗口的状态计算完成后，清除窗口中最老的sample，等待记录新的sample
 * Subclasses of this class define different statistics measured using this basic pattern.
 */
public abstract class SampledStat implements MeasurableStat {

    private double initialValue;
    private int current = 0;
    protected List<Sample> samples;

    public SampledStat(double initialValue) {
        this.initialValue = initialValue;
        this.samples = new ArrayList<>(2);
    }

    // 记录事件，填充样本
    @Override
    public void record(MetricConfig config, double value, long timeMs) {
        Sample sample = current(timeMs);
        // 当前sample已经成熟（过期时间内没有事件发生或者事件数量达到阈值）
        if (sample.isComplete(timeMs, config))
            // 获取下一个sample
            sample = advance(config, timeMs);
        // 更新sample的样本值
        update(sample, config, value, timeMs);
        // 更新sample的事件数量
        sample.eventCount += 1;
    }

    private Sample advance(MetricConfig config, long timeMs) {
        this.current = (this.current + 1) % config.samples();
        // 窗口尚未填满，新建样本
        if (this.current >= samples.size()) {
            Sample sample = newSample(timeMs);
            this.samples.add(sample);
            return sample;
        } else {
            // 窗口已满，覆盖旧样本
            Sample sample = current(timeMs);
            sample.reset(timeMs);
            return sample;
        }
    }

    protected Sample newSample(long timeMs) {
        return new Sample(this.initialValue, timeMs);
    }

    // 计算统计值
    @Override
    public double measure(MetricConfig config, long now) {
        // 清理无效样本
        purgeObsoleteSamples(config, now);
        return combine(this.samples, config, now);
    }

    public Sample current(long timeMs) {
        if (samples.size() == 0)
            this.samples.add(newSample(timeMs));
        return this.samples.get(this.current);
    }

    public Sample oldest(long now) {
        if (samples.size() == 0)
            this.samples.add(newSample(now));
        Sample oldest = this.samples.get(0);
        for (int i = 1; i < this.samples.size(); i++) {
            Sample curr = this.samples.get(i);
            if (curr.lastWindowMs < oldest.lastWindowMs)
                oldest = curr;
        }
        return oldest;
    }

    @Override
    public String toString() {
        return "SampledStat(" +
            "initialValue=" + initialValue +
            ", current=" + current +
            ", samples=" + samples +
            ')';
    }

    protected abstract void update(Sample sample, MetricConfig config, double value, long timeMs);

    public abstract double combine(List<Sample> samples, MetricConfig config, long now);

    /* Timeout any windows that have expired in the absence of any events
    * 在超过过期时间没有事件发生时，回收sample（初始化样本值和事件数量） */
    protected void purgeObsoleteSamples(MetricConfig config, long now) {
        long expireAge = config.samples() * config.timeWindowMs();
        for (Sample sample : samples) {
            if (now - sample.lastWindowMs >= expireAge)
                sample.reset(now);
        }
    }

    protected static class Sample {
        public double initialValue;
        // 事件数量
        public long eventCount;
        // 事件最后发生事件
        public long lastWindowMs;
        // 样本值
        public double value;

        public Sample(double initialValue, long now) {
            this.initialValue = initialValue;
            this.eventCount = 0;
            this.lastWindowMs = now;
            this.value = initialValue;
        }

        public void reset(long now) {
            this.eventCount = 0;
            this.lastWindowMs = now;
            this.value = initialValue;
        }

        public boolean isComplete(long timeMs, MetricConfig config) {
            return timeMs - lastWindowMs >= config.timeWindowMs() || eventCount >= config.eventWindow();
        }

        @Override
        public String toString() {
            return "Sample(" +
                "value=" + value +
                ", eventCount=" + eventCount +
                ", lastWindowMs=" + lastWindowMs +
                ", initialValue=" + initialValue +
                ')';
        }
    }

}
