package eventstore
package redis

import org.joda.time.DateTimeUtils
import play.api.libs.json._
import _root_.redis.clients.jedis._
import scala.annotation.tailrec
import scala.collection.JavaConverters._

/**
 * Redis event committer that uses the WATCH/MULTI/EXEC commands to ensure the event store's
 * invariants are maintained. This committer requires two round-trips to the Redis DB for
 * every commit and will have high contention on the `commitsKey` hash when many processes
 * try to commit concurrently.
 */
trait RedisWatchMultiExecEventCommitter[Event] { this: RedisEventStore[Event] =>
  object committer extends EventCommitter[Event] {
    import reader._

    private[this] val lock = new Object

    override def tryCommit(streamId: String, expected: StreamRevision, event: Event): CommitResult[Event] = {
      val backoff = new Backoff
      val streamKey = keyForStream(streamId)
      val result = withJedis { implicit jedis =>
        @tailrec def tryCommitWithRetry: Either[StreamRevision, Commit[Event]] = {
          val (storeRevision, actual) = prepareCommit(streamKey)

          if (expected != actual) {
            abortCommit(streamId, actual, expected)
          } else {
            val commit = Commit(storeRevision.next, DateTimeUtils.currentTimeMillis, streamId, actual.next, Seq(event))
            if (doCommit(streamKey, commit)) {
              Right(commit)
            } else {
              backoff.once
              tryCommitWithRetry
            }
          }
        }

        // Avoid Redis commit contention from within the same JVM.
        lock.synchronized {
          tryCommitWithRetry
        }
      }

      result.left.map { actual =>
        val conflicting = readStream(streamId, since = expected)
        Conflict(streamId, actual, expected, conflicting)
      }
    }

    private[this] def prepareCommit(streamKey: String)(implicit jedis: Jedis): (StoreRevision, StreamRevision) = {
      val pipeline = jedis.pipelined
      pipeline.watch(CommitsKey, streamKey)
      val storeRevision = pipeline.hlen(CommitsKey)
      val streamRevision = pipeline.llen(streamKey)
      pipeline.sync

      (StoreRevision(storeRevision.get), StreamRevision(streamRevision.get))
    }

    private[this] def abortCommit(streamId: String, actual: StreamRevision, expected: StreamRevision)(implicit jedis: Jedis) = {
      jedis.unwatch()
      if (expected < actual) Left(actual)
      else throw new IllegalArgumentException("expected revision %d is greater than actual revision %d for %s" format (expected.value, actual.value, streamId))
    }

    private[this] def doCommit(streamKey: String, commit: Commit[Event])(implicit jedis: Jedis): Boolean = {
      val commitId = commit.storeRevision.value.toString
      val commitData = serializeCommit(commit)

      val pipeline = jedis.pipelined
      pipeline.multi()
      pipeline.hset(CommitsKey, commitId, commitData)
      pipeline.rpush(streamKey, commitId)
      pipeline.publish(CommitsKey, commitData)
      val exec = pipeline.exec
      pipeline.sync

      exec.get != null
    }
  }

  private[this] class Backoff {
    var tries: Int = 0

    def once: Unit = {
      Thread.sleep(tries * (util.Random.nextInt(10) + 1))
      tries += 1
    }
  }
}