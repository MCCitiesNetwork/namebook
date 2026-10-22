package com.minecraftcitiesnetwork.namebook.paper.listener;

import com.minecraftcitiesnetwork.namebook.core.chain.LookupChain;
import com.minecraftcitiesnetwork.namebook.core.store.PlayerStore;
import net.kyori.adventure.text.Component;
import org.apache.ibatis.exceptions.PersistenceException;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class JoinRecorderTest {

    private static final UUID ALEX = UUID.fromString("ec561538-f3fd-461d-aff5-086b22154bce");
    private static final UUID STEVE = UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7");

    private final PlayerStore store = mock(PlayerStore.class);
    private final LookupChain chain = mock(LookupChain.class);
    private final MutableClock clock = new MutableClock();
    private final List<LogRecord> warnings = new ArrayList<>();
    private final Logger log = capturingLogger();

    @Test
    void join_updatesCacheBeforeDatabase() {
        List<Runnable> queued = new ArrayList<>();
        JoinRecorder recorder = new JoinRecorder(this.store, this.chain, queued::add, this.clock, this.log);

        recorder.onJoin(joinOf(player(ALEX, "Alex")));

        // The cache is right at once; the database write is still waiting on its executor.
        verify(this.chain).recordLocally(ALEX, "Alex");
        verifyNoInteractions(this.store);
        assertEquals(1, queued.size());

        queued.forEach(Runnable::run);
        InOrder order = inOrder(this.chain, this.store);
        order.verify(this.chain).recordLocally(ALEX, "Alex");
        order.verify(this.store).record(ALEX, "Alex");
    }

    @Test
    void join_databaseDown_logsOncePerMinute() {
        doThrow(new PersistenceException("connection refused")).when(this.store).record(any(), any());
        JoinRecorder recorder = new JoinRecorder(this.store, this.chain, Runnable::run, this.clock, this.log);
        Player alex = player(ALEX, "Alex");
        Player steve = player(STEVE, "Steve");

        recorder.onJoin(joinOf(alex));
        recorder.onJoin(joinOf(alex));
        this.clock.advance(Duration.ofSeconds(30));
        recorder.onJoin(joinOf(alex));
        assertEquals(1, this.warnings.size(), "one warning per player within a minute");

        // Each player has their own minute.
        recorder.onJoin(joinOf(steve));
        assertEquals(2, this.warnings.size());

        this.clock.advance(Duration.ofSeconds(31));
        recorder.onJoin(joinOf(alex));
        assertEquals(3, this.warnings.size(), "warns again once the minute is up");

        // The cache was still updated every time: the join itself is unaffected.
        verify(this.chain, org.mockito.Mockito.times(4)).recordLocally(ALEX, "Alex");
    }

    @Test
    void join_executorRejects_doesNotThrow() {
        JoinRecorder recorder = new JoinRecorder(this.store, this.chain, task -> {
            throw new java.util.concurrent.RejectedExecutionException("shutting down");
        }, this.clock, this.log);

        recorder.onJoin(joinOf(player(ALEX, "Alex")));

        verify(this.chain).recordLocally(ALEX, "Alex");
        assertEquals(1, this.warnings.size());
    }

    @Test
    void recordOnline_recordsEveryone() {
        JoinRecorder recorder = new JoinRecorder(this.store, this.chain, Runnable::run, this.clock, this.log);

        recorder.recordOnline(List.of(player(ALEX, "Alex"), player(STEVE, "Steve")));

        verify(this.chain).recordLocally(ALEX, "Alex");
        verify(this.chain).recordLocally(STEVE, "Steve");
        verify(this.store).record(ALEX, "Alex");
        verify(this.store).record(STEVE, "Steve");
    }

    private static Player player(UUID id, String name) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getName()).thenReturn(name);
        return player;
    }

    private static PlayerJoinEvent joinOf(Player player) {
        return new PlayerJoinEvent(player, Component.empty());
    }

    private Logger capturingLogger() {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    JoinRecorderTest.this.warnings.add(record);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        return logger;
    }

    private static final class MutableClock extends Clock {

        private Instant now = Instant.parse("2026-10-22T12:00:00Z");

        void advance(Duration by) {
            this.now = this.now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return this.now;
        }
    }
}
