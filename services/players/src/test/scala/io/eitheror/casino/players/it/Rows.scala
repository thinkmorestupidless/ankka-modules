package io.eitheror.casino.players.it

import io.eitheror.casino.players.PlayersCodecs
import io.eitheror.casino.players.player.PlayerRow

/**
 * Rows written straight into a view's table, for the lists at scale (spec 030 SC-005): 10,000 players registered
 * through the route take minutes (DuplicatesScaleSuite), and what a list is tested for is its query, not the fold,
 * which ViewsSuite tests. Each row is the view's own serialized form.
 */
object Rows:
  private val playerRow = PlayersCodecs.serializer[PlayerRow]("player-row")

  /** Upserts `rows` into the players view's table, in batches. */
  def players(kit: Kit, rows: Seq[PlayerRow]): Unit =
    val c = java.sql.DriverManager.getConnection(kit.testKit.jdbcUrl, "ankka", "ankka")
    try
      c.setAutoCommit(false)
      val st = c.prepareStatement(
        "INSERT INTO ankka_view_players (row_key, payload) VALUES (?, ?) ON CONFLICT (row_key) DO UPDATE SET payload = EXCLUDED.payload")
      rows.grouped(1000).foreach { batch =>
        batch.foreach { r =>
          st.setString(1, r.playerId)
          st.setString(2, String(playerRow.toBytes(r), "UTF-8"))
          st.addBatch()
        }
        st.executeBatch(): Unit
      }
      c.commit()
    finally c.close()
