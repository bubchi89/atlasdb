package com.palantir.nexus.db;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Iterables;
import com.palantir.common.proxy.TimingProxy;
import com.palantir.util.timer.LoggingOperationTimer;

public class ThreadConfinedProxyTest extends Assert {

    Logger log = LoggerFactory.getLogger(ThreadConfinedProxyTest.class);

    String testString = "test";

    @Test
    public void testCurrentThreadCanCreateAndUseSubject() {
        @SuppressWarnings("unchecked")
        List<String> subject = ThreadConfinedProxy.newProxyInstance(List.class, new ArrayList<String>(),
                ThreadConfinedProxy.Strictness.VALIDATE);
        subject.add(testString);
        assertEquals(testString, Iterables.getOnlyElement(subject));
    }

    @Test
    public void testExplicitThreadCanCreateAndUseSubject() throws InterruptedException {

        final AtomicReference<List<String>> inputReference = new AtomicReference<>(null);
        final AtomicBoolean outputReference = new AtomicBoolean(false);

        Thread childThread = new Thread(() -> {
            List<String> subjectInChildThread = inputReference.get();
            subjectInChildThread.add(testString);
            if (Iterables.getOnlyElement(subjectInChildThread).equals(testString)) {
                outputReference.set(Boolean.TRUE);
            }
        });

        @SuppressWarnings("unchecked")
        List<String> subject = ThreadConfinedProxy.newProxyInstance(List.class, new ArrayList<String>(),
                ThreadConfinedProxy.Strictness.VALIDATE, childThread);
        inputReference.set(subject);
        childThread.start();
        childThread.join(10000);

        assertTrue(outputReference.get());
    }

    @Test
    public void testExplicitThreadCannotAndUseSubjectFromMainThread() throws InterruptedException {

        final AtomicReference<List<String>> inputReference = new AtomicReference<>(null);
        final AtomicBoolean outputReference = new AtomicBoolean(false);

        Thread childThread = new Thread(() -> {
            outputReference.set(true);
            List<String> subjectInChildThread = inputReference.get();
            subjectInChildThread.add(testString);
            // Should fail
            outputReference.set(false);

        });

        @SuppressWarnings("unchecked")
        List<String> subject = ThreadConfinedProxy.newProxyInstance(List.class, new ArrayList<String>(),
                ThreadConfinedProxy.Strictness.VALIDATE);
        inputReference.set(subject);
        childThread.start();
        childThread.join(10000);

        assertTrue(outputReference.get());
    }


    @Test
    public void testMainThreadCanDelegateToExplicitThreadAndLoseAccessAndAbilityToDelegate() throws InterruptedException {

        final AtomicReference<List<String>> inputReference = new AtomicReference<>(null);
        final AtomicInteger outputReference = new AtomicInteger(0);

        final Thread mainThread = Thread.currentThread();

        Thread childThread = new Thread(() -> {
            outputReference.compareAndSet(0, 1);
            List<String> subjectInChildThread = inputReference.get();
            ThreadConfinedProxy.changeThread(subjectInChildThread, mainThread, Thread.currentThread());
            subjectInChildThread.add(testString);
            if (Iterables.getOnlyElement(subjectInChildThread).equals(testString)) {
                outputReference.compareAndSet(1, 2);
            }

        });

        @SuppressWarnings("unchecked")
        List<String> subject = ThreadConfinedProxy.newProxyInstance(List.class, new ArrayList<String>(),
                ThreadConfinedProxy.Strictness.VALIDATE);
        inputReference.set(subject);
        childThread.start();
        childThread.join(10000);

        assertEquals(2, outputReference.get());

        // Cannot be access from main thread anymore
        try {
            subject.add(testString);
            fail();
        } catch (Exception e) {
            outputReference.compareAndSet(2, 3);
        }

        assertEquals(3, outputReference.get());

        // Cannot give to another thread because main thread does not own it
        Thread otherThread = new Thread(() -> {
            outputReference.compareAndSet(3, 4);
            List<String> subjectInChildThread = inputReference.get();
            ThreadConfinedProxy.changeThread(subjectInChildThread, mainThread, Thread.currentThread());
            outputReference.compareAndSet(4, 5);
        });

        otherThread.start();
        otherThread.join(10000);
        assertEquals(4, outputReference.get());

        // Cannot give away from main thread either
        try {
            ThreadConfinedProxy.changeThread(subject, mainThread, otherThread);
            fail();
        } catch (Exception e) {
            outputReference.compareAndSet(4, 5);
        }
        assertEquals(5, outputReference.get());
    }



    @Test
    public void testChildThreadCanDelegateBackToMainThread() throws InterruptedException {

        final AtomicReference<List<String>> inputReference = new AtomicReference<>(null);
        final AtomicInteger outputReference = new AtomicInteger(0);

        final Thread mainThread = Thread.currentThread();

        Thread childThread = new Thread(() -> {
            outputReference.compareAndSet(0, 1);
            List<String> subjectInChildThread = inputReference.get();
            ThreadConfinedProxy.changeThread(subjectInChildThread, mainThread, Thread.currentThread());
            subjectInChildThread.add(testString);
            if (Iterables.getOnlyElement(subjectInChildThread).equals(testString)) {
                outputReference.compareAndSet(1, 2);
            }
            ThreadConfinedProxy.changeThread(subjectInChildThread, Thread.currentThread(), mainThread);
        });

        @SuppressWarnings("unchecked")
        List<String> subject = ThreadConfinedProxy.newProxyInstance(List.class, new ArrayList<String>(),
                ThreadConfinedProxy.Strictness.VALIDATE);
        inputReference.set(subject);
        childThread.start();
        childThread.join(10000);

        assertEquals(2, outputReference.get());

        // We got delegated back, so we can use subject again
        assertEquals(testString, Iterables.getOnlyElement(subject));
    }


    @Test
    @SuppressWarnings("unchecked")
    public void testDelegationCanHandleMoreProxies() throws InterruptedException {

        final AtomicReference<List<String>> inputReference = new AtomicReference<>(null);
        final AtomicInteger outputReference = new AtomicInteger(0);

        final Thread mainThread = Thread.currentThread();

        Thread childThread = new Thread(() -> {
            outputReference.compareAndSet(0, 1);
            List<String> subjectInChildThread = inputReference.get();
            ThreadConfinedProxy.changeThread(subjectInChildThread, mainThread, Thread.currentThread());
            subjectInChildThread.add(testString);
            if (Iterables.getOnlyElement(subjectInChildThread).equals(testString)) {
                outputReference.compareAndSet(1, 2);
            }
            ThreadConfinedProxy.changeThread(subjectInChildThread, Thread.currentThread(), mainThread);
        });

        // Make sure subject is wrapped in proxies, including multiple ThreadConfinedProxy objects, and also does not start with a
        // ThreadConfinedProxy
        List<String> subject = new ArrayList<>();
        subject = ThreadConfinedProxy.newProxyInstance(List.class, subject,
                ThreadConfinedProxy.Strictness.VALIDATE);
        subject = TimingProxy.newProxyInstance(List.class, subject, LoggingOperationTimer.create(log));
        subject = ThreadConfinedProxy.newProxyInstance(List.class, subject,
                ThreadConfinedProxy.Strictness.VALIDATE);
        subject = TimingProxy.newProxyInstance(List.class, subject, LoggingOperationTimer.create(log));

        inputReference.set(subject);
        childThread.start();
        childThread.join(10000);

        assertEquals(2, outputReference.get());

        // We got delegated back, so we can use subject again
        assertEquals(testString, Iterables.getOnlyElement(subject));
    }
}

