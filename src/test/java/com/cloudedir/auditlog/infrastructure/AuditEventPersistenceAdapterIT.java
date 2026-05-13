package com.cloudedir.auditlog.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.cloudedir.auditlog.application.port.in.AuditEventQuery;
import com.cloudedir.auditlog.application.port.in.KeysetPosition;
import com.cloudedir.auditlog.domain.model.AuditEvent;
import com.cloudedir.auditlog.infrastructure.persistence.adapter.AuditEventPersistenceAdapter;
import com.cloudedir.auditlog.infrastructure.persistence.repository.AuditEventJpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class AuditEventPersistenceAdapterIT {

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired AuditEventPersistenceAdapter adapter;
  @Autowired AuditEventJpaRepository repository;

  @Test
  void savesAndLoadsEvent() {
    var event =
        new AuditEvent(UUID.randomUUID(), "user-1", "LOGIN", "SESSION", null, null, Instant.now());

    adapter.save(event);

    var loaded = adapter.findById(event.id());
    assertThat(loaded).isPresent();
    assertThat(loaded.get().actor()).isEqualTo("user-1");
  }

  @Test
  void findReturnsRowsOrderedByTimestampAscThenIdAsc() {
    var actor = "actor-" + UUID.randomUUID();
    var base = Instant.parse("2020-01-01T00:00:00Z");
    var tied1 =
        new AuditEvent(new UUID(0, 1), actor, "LOGIN", "SESSION", "r-1", null, base.plusSeconds(1));
    var tied2 =
        new AuditEvent(new UUID(0, 2), actor, "LOGIN", "SESSION", "r-1", null, base.plusSeconds(1));
    var later =
        new AuditEvent(new UUID(0, 3), actor, "LOGIN", "SESSION", "r-1", null, base.plusSeconds(2));
    adapter.save(later);
    adapter.save(tied2);
    adapter.save(tied1);

    var query = new AuditEventQuery(base, base.plusSeconds(60), actor, null, null, 10, null);

    var firstCall = adapter.find(query);
    var secondCall = adapter.find(query);

    assertThat(firstCall)
        .extracting(AuditEvent::id)
        .containsExactly(tied1.id(), tied2.id(), later.id());
    assertThat(secondCall).isEqualTo(firstCall);
  }

  @Test
  void findAppliesHalfOpenRange() {
    var actor = "actor-" + UUID.randomUUID();
    var from = Instant.parse("2020-02-01T00:00:00Z");
    var to = Instant.parse("2020-02-01T00:01:00Z");
    var atFrom = new AuditEvent(UUID.randomUUID(), actor, "LOGIN", "SESSION", null, null, from);
    var inside =
        new AuditEvent(
            UUID.randomUUID(), actor, "LOGIN", "SESSION", null, null, from.plusSeconds(30));
    var atTo = new AuditEvent(UUID.randomUUID(), actor, "LOGIN", "SESSION", null, null, to);
    adapter.save(atFrom);
    adapter.save(inside);
    adapter.save(atTo);

    var result = adapter.find(new AuditEventQuery(from, to, actor, null, null, 10, null));

    assertThat(result)
        .extracting(AuditEvent::id)
        .containsExactly(atFrom.id(), inside.id())
        .doesNotContain(atTo.id());
  }

  @Test
  void findRespectsLimit() {
    var actor = "actor-" + UUID.randomUUID();
    var from = Instant.parse("2020-03-01T00:00:00Z");
    var to = from.plusSeconds(3600);
    for (int i = 0; i < 5; i++) {
      adapter.save(
          new AuditEvent(
              UUID.randomUUID(), actor, "LOGIN", "SESSION", null, null, from.plusSeconds(i)));
    }

    var result = adapter.find(new AuditEventQuery(from, to, actor, null, null, 3, null));

    assertThat(result).hasSize(3);
  }

  @Test
  void keysetPagingReturnsEachRowExactlyOnceAndIsStableUnderConcurrentAppends() {
    var actor = "actor-" + UUID.randomUUID();
    var from = Instant.parse("2020-04-01T00:00:00Z");
    var to = from.plusSeconds(3600);

    var page1Rows =
        List.of(
            new AuditEvent(
                UUID.randomUUID(), actor, "LOGIN", "SESSION", null, null, from.plusSeconds(1)),
            new AuditEvent(
                UUID.randomUUID(), actor, "LOGIN", "SESSION", null, null, from.plusSeconds(2)));
    var page2Rows =
        List.of(
            new AuditEvent(
                UUID.randomUUID(), actor, "LOGIN", "SESSION", null, null, from.plusSeconds(3)),
            new AuditEvent(
                UUID.randomUUID(), actor, "LOGIN", "SESSION", null, null, from.plusSeconds(4)));
    page1Rows.forEach(adapter::save);
    page2Rows.forEach(adapter::save);

    var k = 2;
    var firstPage = adapter.find(new AuditEventQuery(from, to, actor, null, null, k, null));
    assertThat(firstPage).hasSize(k);

    // Concurrent appends after page 1 is read but before page 2 is read. Two of these land before
    // the cursor (and so must be skipped by page 2), two land after (and must be visible).
    var beforeCursor1 =
        new AuditEvent(
            UUID.randomUUID(),
            actor,
            "LOGIN",
            "SESSION",
            null,
            null,
            from.plusSeconds(1).plusMillis(500));
    var beforeCursor2 =
        new AuditEvent(
            UUID.randomUUID(),
            actor,
            "LOGIN",
            "SESSION",
            null,
            null,
            from.plusSeconds(2).minusMillis(1));
    var afterCursor1 =
        new AuditEvent(
            UUID.randomUUID(), actor, "LOGIN", "SESSION", null, null, from.plusSeconds(5));
    var afterCursor2 =
        new AuditEvent(
            UUID.randomUUID(), actor, "LOGIN", "SESSION", null, null, from.plusSeconds(6));
    adapter.save(beforeCursor1);
    adapter.save(beforeCursor2);
    adapter.save(afterCursor1);
    adapter.save(afterCursor2);

    var lastOfPage1 = firstPage.get(firstPage.size() - 1);
    var cursor = new KeysetPosition(lastOfPage1.timestamp(), lastOfPage1.id());

    var secondPage = adapter.find(new AuditEventQuery(from, to, actor, null, null, k, cursor));

    assertThat(secondPage)
        .extracting(AuditEvent::id)
        .doesNotContainAnyElementsOf(firstPage.stream().map(AuditEvent::id).toList());
    assertThat(secondPage)
        .extracting(AuditEvent::id)
        .containsExactly(page2Rows.get(0).id(), page2Rows.get(1).id());

    // Walk the rest of the pages to verify "no gaps" — every row strictly after the cursor must
    // appear exactly once across subsequent pages, with no duplicates.
    var seen = new java.util.ArrayList<UUID>();
    seen.addAll(firstPage.stream().map(AuditEvent::id).toList());
    var pageCursor = cursor;
    while (true) {
      var page = adapter.find(new AuditEventQuery(from, to, actor, null, null, k, pageCursor));
      if (page.isEmpty()) break;
      page.forEach(e -> seen.add(e.id()));
      var last = page.get(page.size() - 1);
      pageCursor = new KeysetPosition(last.timestamp(), last.id());
    }

    assertThat(seen).doesNotHaveDuplicates();
    assertThat(seen)
        .contains(
            page1Rows.get(0).id(),
            page1Rows.get(1).id(),
            page2Rows.get(0).id(),
            page2Rows.get(1).id(),
            afterCursor1.id(),
            afterCursor2.id());
    // The two rows that landed before the cursor must not reappear on subsequent pages.
    assertThat(seen).doesNotContain(beforeCursor1.id(), beforeCursor2.id());
  }

  @Test
  void findAppliesOptionalFilterCombinations() {
    var actor = "actor-" + UUID.randomUUID();
    var otherActor = "other-" + UUID.randomUUID();
    var from = Instant.parse("2020-05-01T00:00:00Z");
    var to = from.plusSeconds(3600);

    var match =
        new AuditEvent(
            UUID.randomUUID(), actor, "LOGIN", "DOC", "doc-1", null, from.plusSeconds(1));
    var sameActorOtherResource =
        new AuditEvent(
            UUID.randomUUID(), actor, "LOGIN", "DOC", "doc-2", null, from.plusSeconds(2));
    var otherActorSameResource =
        new AuditEvent(
            UUID.randomUUID(), otherActor, "LOGIN", "DOC", "doc-1", null, from.plusSeconds(3));
    var differentResourceType =
        new AuditEvent(
            UUID.randomUUID(), actor, "LOGIN", "FOLDER", "doc-1", null, from.plusSeconds(4));
    adapter.save(match);
    adapter.save(sameActorOtherResource);
    adapter.save(otherActorSameResource);
    adapter.save(differentResourceType);

    var allFour =
        List.of(
            match.id(),
            sameActorOtherResource.id(),
            otherActorSameResource.id(),
            differentResourceType.id());

    // Range only — disambiguate from other test rows via a unique actor set. We use IDs to filter
    // assertions because other tests share the table.
    var rangeOnly = adapter.find(new AuditEventQuery(from, to, null, null, null, 100, null));
    assertThat(idsOf(rangeOnly)).containsAll(allFour); // all four rows are visible via range alone

    // (a) actor only
    var byActor = adapter.find(new AuditEventQuery(from, to, actor, null, null, 100, null));
    assertThat(idsOf(byActor))
        .containsExactlyInAnyOrder(
            match.id(), sameActorOtherResource.id(), differentResourceType.id());

    // (b) resourceType only
    var byResourceType =
        adapter.find(new AuditEventQuery(from, to, null, "FOLDER", null, 100, null));
    assertThat(idsOf(byResourceType)).contains(differentResourceType.id());
    assertThat(idsOf(byResourceType))
        .doesNotContain(match.id(), sameActorOtherResource.id(), otherActorSameResource.id());

    // (c) resourceId only
    var byResourceId = adapter.find(new AuditEventQuery(from, to, null, null, "doc-2", 100, null));
    assertThat(idsOf(byResourceId)).contains(sameActorOtherResource.id());
    assertThat(idsOf(byResourceId)).doesNotContain(match.id(), otherActorSameResource.id());

    // (d) resourceType + resourceId
    var byTypeAndId = adapter.find(new AuditEventQuery(from, to, null, "DOC", "doc-1", 100, null));
    assertThat(idsOf(byTypeAndId))
        .containsExactlyInAnyOrder(match.id(), otherActorSameResource.id());

    // (e) actor + resource filter
    var byActorAndResource =
        adapter.find(new AuditEventQuery(from, to, actor, "DOC", "doc-1", 100, null));
    assertThat(idsOf(byActorAndResource)).containsExactly(match.id());
  }

  @Test
  void findHasNoSideEffects() {
    var actor = "actor-" + UUID.randomUUID();
    var from = Instant.parse("2020-06-01T00:00:00Z");
    var seeded =
        new AuditEvent(
            UUID.randomUUID(), actor, "LOGIN", "SESSION", null, null, from.plusSeconds(1));
    adapter.save(seeded);

    var countBefore = repository.count();
    var beforeTimestamp = repository.findById(seeded.id()).orElseThrow().getTimestamp();

    adapter.find(new AuditEventQuery(from, from.plusSeconds(3600), actor, null, null, 10, null));

    assertThat(repository.count()).isEqualTo(countBefore);
    assertThat(repository.findById(seeded.id()).orElseThrow().getTimestamp())
        .isEqualTo(beforeTimestamp);
  }

  private static List<UUID> idsOf(List<AuditEvent> events) {
    return events.stream().map(AuditEvent::id).toList();
  }
}
