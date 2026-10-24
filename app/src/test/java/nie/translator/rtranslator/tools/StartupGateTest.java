package nie.translator.rtranslator.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

public class StartupGateTest {

    @Test
    public void secondScreenJoinsTheLoadAlreadyRunning() {
        ModelStartupGate gate = new ModelStartupGate();
        AtomicInteger ready = new AtomicInteger();
        ModelStartupGate.Observer first = observer(ready);
        ModelStartupGate.Observer second = observer(ready);

        ModelStartupGate.Join started = gate.beginOrJoin(first);
        ModelStartupGate.Join joined = gate.beginOrJoin(second);

        assertTrue(started.start);
        assertFalse(joined.start);
        assertEquals(started.runId, joined.runId);
        assertTrue(gate.isRunning());
        assertEquals(0, ready.get());

        gate.completeSuccess(started.runId);

        assertTrue(gate.isReady());
        assertEquals(2, ready.get());
        assertFalse(gate.beginOrJoin(observer(ready)).start);
        assertEquals(2, ready.get());
    }

    @Test
    public void failureStaysUntilTheUserRetries() {
        ModelStartupGate gate = new ModelStartupGate();
        AtomicInteger failed = new AtomicInteger();
        ModelStartupGate.Join started = gate.beginOrJoin(new ModelStartupGate.Observer() {
            @Override
            public void onReady() {
            }

            @Override
            public void onFailed(int[] reasons, long value) {
                failed.incrementAndGet();
                assertEquals(7, reasons[0]);
                assertEquals(4L, value);
            }
        });

        gate.completeFailure(started.runId, new int[]{7}, 4L);

        assertTrue(gate.hasFailure());
        assertFalse(gate.beginOrJoin(observer(new AtomicInteger())).start);
        assertEquals(1, failed.get());
        assertEquals(7, gate.failureReasons()[0]);

        int discarded = started.runId;
        gate.clearFailure();
        ModelStartupGate.Join retry = gate.beginOrJoin(observer(new AtomicInteger()));
        gate.completeSuccess(discarded);

        assertTrue(retry.start);
        assertTrue(gate.isRunning());
        assertFalse(gate.isReady());

        gate.completeSuccess(retry.runId);
        assertTrue(gate.isReady());
    }

    @Test
    public void removedScreenDoesNotReceiveTheResult() {
        ModelStartupGate gate = new ModelStartupGate();
        AtomicInteger ready = new AtomicInteger();
        ModelStartupGate.Observer gone = observer(ready);
        ModelStartupGate.Join started = gate.beginOrJoin(gone);
        gate.remove(gone);
        gate.beginOrJoin(observer(ready));

        gate.completeSuccess(started.runId);

        assertEquals(1, ready.get());
    }

    @Test
    public void secondCallerAttachesToTheOpenSession() {
        SessionInitGate gate = new SessionInitGate();
        AtomicInteger started = new AtomicInteger();
        AtomicInteger ready = new AtomicInteger();

        SessionInitGate.Join first = gate.join(successListener(ready));
        SessionInitGate.Join second = gate.join(successListener(ready));
        if (first.start) {
            started.incrementAndGet();
        }
        if (second.start) {
            started.incrementAndGet();
        }

        assertEquals(1, started.get());
        assertEquals(0, ready.get());
        gate.succeed(first.generation);
        assertEquals(2, ready.get());
        assertTrue(gate.isReady());

        SessionInitGate.Join again = gate.join(successListener(ready));
        assertFalse(again.start);
        assertEquals(3, ready.get());
    }

    @Test
    public void discardedSessionCannotFinishTheNextOne() {
        SessionInitGate gate = new SessionInitGate();
        AtomicInteger ready = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        SessionInitGate.Join first = gate.join(new SessionInitGate.Listener() {
            @Override
            public void onSuccess() {
                ready.incrementAndGet();
            }

            @Override
            public void onFailure(int[] reasons, long value) {
                failed.incrementAndGet();
            }
        });

        gate.reset();
        gate.succeed(first.generation);
        gate.fail(first.generation, new int[]{1}, 0);

        assertEquals(0, ready.get());
        assertEquals(0, failed.get());
        assertFalse(gate.isReady());
        assertFalse(gate.isRunning());

        SessionInitGate.Join next = gate.join(successListener(ready));
        assertTrue(next.start);
        gate.succeed(next.generation);
        assertEquals(1, ready.get());
    }

    private static ModelStartupGate.Observer observer(final AtomicInteger ready) {
        return new ModelStartupGate.Observer() {
            @Override
            public void onReady() {
                ready.incrementAndGet();
            }

            @Override
            public void onFailed(int[] reasons, long value) {
            }
        };
    }

    private static SessionInitGate.Listener successListener(final AtomicInteger ready) {
        return new SessionInitGate.Listener() {
            @Override
            public void onSuccess() {
                ready.incrementAndGet();
            }

            @Override
            public void onFailure(int[] reasons, long value) {
            }
        };
    }
}
