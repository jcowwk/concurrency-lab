package com.example.concurrencylab.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 동시성 재현용 실행기.
 *
 * threadCount 개의 작업을 스레드 풀에 올려두고, startGate 로 한 번에 풀어
 * 최대한 같은 순간에 임계 구역에 진입시킨다. 각 작업의 성공/실패 수와
 * 발생한 예외를 모아서 돌려준다.
 */
public final class ConcurrencyRunner {

    private ConcurrencyRunner() {
    }

    public record Result(int success, int failure, List<Throwable> exceptions) {
    }

    @FunctionalInterface
    public interface ThrowingIntConsumer {
        void accept(int index) throws Exception;
    }

    public static Result run(int threadCount, ThrowingIntConsumer task) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(threadCount, 50));
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(threadCount);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failure = new AtomicInteger();
        List<Throwable> exceptions = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            pool.submit(() -> {
                try {
                    startGate.await();
                    task.accept(index);
                    success.incrementAndGet();
                } catch (Throwable t) {
                    failure.incrementAndGet();
                    exceptions.add(t);
                } finally {
                    endGate.countDown();
                }
            });
        }

        startGate.countDown();
        endGate.await();
        pool.shutdown();

        return new Result(success.get(), failure.get(), exceptions);
    }
}
