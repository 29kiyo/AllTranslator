package com.kiyo.alltranslator.service;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Phase 14: java.util.concurrent.Semaphore has no public "set total permits to
 * X" operation - only relative acquire()/release() and a *protected*
 * reducePermits(int) for shrinking. This thin subclass exposes a safe, absolute
 * setTotalPermits(int) by tracking the current total itself and translating a
 * change into the appropriate release()/reducePermits() call.
 *
 * Both release() and reducePermits() are documented by the JDK as safe to call
 * concurrently with in-progress acquire()/release() calls from other threads,
 * so this is safe to invoke from the config-screen save path (main/render
 * thread) while translation requests are concurrently acquiring/releasing
 * permits on the async translation executor.
 *
 * Shrinking below the number of currently-held permits is intentional and safe
 * per Semaphore's own Javadoc: availablePermits() simply goes negative until
 * enough release() calls (from in-flight requests finishing) bring it back up,
 * which is exactly the desired "stop starting new ones until we're back under
 * the new, lower limit" behavior.
 */
final class ResizableSemaphore extends Semaphore {

    private final AtomicInteger totalPermits;

    ResizableSemaphore(int initialPermits) {
        super(initialPermits);
        this.totalPermits = new AtomicInteger(initialPermits);
    }

    /** Grows or shrinks the total permit count to exactly newTotal (minimum 1). */
    synchronized void setTotalPermits(int newTotal) {
        int clamped = Math.max(1, newTotal);
        int current = totalPermits.getAndSet(clamped);
        int delta = clamped - current;
        if (delta > 0) {
            release(delta);
        } else if (delta < 0) {
            reducePermits(-delta);
        }
    }
}
