/**
 * Copyright 2009-2017 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.javacrumbs.shedlock.spring;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.spring.ScheduledLockConfigurationBuilder.ConfiguredScheduledLockConfigurationBuilder;
import net.javacrumbs.shedlock.spring.ScheduledLockConfigurationBuilder.ScheduledLockConfigurationBuilderWithoutDefaultLockAtMostFor;
import net.javacrumbs.shedlock.spring.ScheduledLockConfigurationBuilder.ScheduledLockConfigurationBuilderWithoutTaskScheduler;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.time.temporal.TemporalAmount;
import java.util.concurrent.ScheduledExecutorService;

import static net.javacrumbs.shedlock.spring.SpringLockConfigurationExtractor.DEFAULT_LOCK_AT_MOST_FOR;

class DefaultScheduledLockConfigurationBuilder
    implements ScheduledLockConfigurationBuilder, ScheduledLockConfigurationBuilderWithoutTaskScheduler, ConfiguredScheduledLockConfigurationBuilder, ScheduledLockConfigurationBuilderWithoutDefaultLockAtMostFor {
    private final LockProvider lockProvider;

    private TaskScheduler taskScheduler;

    private TemporalAmount defaultLockAtMostFor = DEFAULT_LOCK_AT_MOST_FOR;

    private TemporalAmount defaultLockAtLeastFor = Duration.ZERO;

    public DefaultScheduledLockConfigurationBuilder(LockProvider lockProvider) {
        this.lockProvider = lockProvider;
    }

    @Override
    public ScheduledLockConfigurationBuilderWithoutDefaultLockAtMostFor withPoolSize(int poolSize) {
        this.taskScheduler = createThreadPoolTaskScheduler(poolSize);
        return this;
    }

    @Override
    public ScheduledLockConfigurationBuilderWithoutDefaultLockAtMostFor withExecutorService(ScheduledExecutorService scheduledExecutorService) {
        this.taskScheduler = new ConcurrentTaskScheduler(scheduledExecutorService);
        return this;
    }

    @Override
    public ScheduledLockConfigurationBuilderWithoutDefaultLockAtMostFor withTaskScheduler(TaskScheduler taskScheduler) {
        this.taskScheduler = taskScheduler;
        return this;
    }


    private static ThreadPoolTaskScheduler createThreadPoolTaskScheduler(int poolSize) {
        ThreadPoolTaskScheduler newTaskScheduler = new ThreadPoolTaskScheduler();
        newTaskScheduler.setPoolSize(poolSize);
        newTaskScheduler.initialize();
        return newTaskScheduler;
    }

    @Override
    public ConfiguredScheduledLockConfigurationBuilder withDefaultLockAtMostFor(TemporalAmount defaultLockAtMostFor) {
        this.defaultLockAtMostFor = defaultLockAtMostFor;
        return this;
    }

    @Override
    public ScheduledLockConfigurationBuilder withDefaultLockAtLeastFor(TemporalAmount defaultLockAtLeastFor) {
        this.defaultLockAtLeastFor = defaultLockAtLeastFor;
        return this;
    }

    @Override
    public ScheduledLockConfiguration build() {
        return new SpringLockableTaskSchedulerFactoryBean(taskScheduler, lockProvider, defaultLockAtMostFor, defaultLockAtLeastFor);
    }
}
