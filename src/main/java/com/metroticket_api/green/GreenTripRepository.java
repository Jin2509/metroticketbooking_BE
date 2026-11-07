package com.metroticket_api.green;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GreenTripRepository extends JpaRepository<GreenTrip, Long> {

    interface Totals {
        long getTrips();
        java.math.BigDecimal getDistanceKm();
        long getCo2Grams();
        long getPoints();
    }

    interface Ranking {
        UUID getUserId();
        long getPoints();
        long getCo2Grams();
        long getTrips();
    }

    boolean existsByTicketIdAndEntryAt(UUID ticketId, Instant entryAt);

    long countByUserIdAndCountedTrueAndExitAtGreaterThanEqual(UUID userId, Instant from);

    @Query("select count(t) as trips, coalesce(sum(t.distanceKm), 0) as distanceKm, "
            + "coalesce(sum(t.co2SavedGrams), 0) as co2Grams, coalesce(sum(t.points), 0) as points "
            + "from GreenTrip t where t.userId = :userId and t.counted = true and t.exitAt >= :from")
    Totals totalsFor(@Param("userId") UUID userId, @Param("from") Instant from);

    @Query("select count(t) as trips, coalesce(sum(t.distanceKm), 0) as distanceKm, "
            + "coalesce(sum(t.co2SavedGrams), 0) as co2Grams, coalesce(sum(t.points), 0) as points "
            + "from GreenTrip t where t.counted = true and t.exitAt >= :from")
    Totals totalsAll(@Param("from") Instant from);

    @Query("select t.userId as userId, sum(t.points) as points, sum(t.co2SavedGrams) as co2Grams, count(t) as trips "
            + "from GreenTrip t where t.counted = true and t.exitAt >= :from and t.exitAt < :to "
            + "group by t.userId order by sum(t.points) desc, sum(t.co2SavedGrams) desc")
    List<Ranking> leaderboard(@Param("from") Instant from, @Param("to") Instant to, Pageable pageable);

    /** Số người có tổng điểm cao hơn trong kỳ → thứ hạng = kết quả + 1. */
    @Query(value = """
            SELECT count(*) FROM (
                SELECT user_id FROM green_trip
                WHERE counted AND exit_at >= :from AND exit_at < :to
                GROUP BY user_id HAVING sum(points) > :points) ahead""", nativeQuery = true)
    long usersAhead(@Param("from") Instant from, @Param("to") Instant to, @Param("points") long points);

    @Query("select t from GreenTrip t join fetch t.entryStation join fetch t.exitStation where t.userId = :userId "
            + "order by t.exitAt desc")
    Page<GreenTrip> findByUser(@Param("userId") UUID userId, Pageable pageable);
}
