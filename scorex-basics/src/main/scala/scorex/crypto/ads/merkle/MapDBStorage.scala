package scorex.crypto.ads.merkle

import java.io.File

import org.mapdb.{DBMaker, HTreeMap, Serializer}
import org.slf4j.LoggerFactory
import scorex.crypto.CryptographicHash.Digest

import scala.util.{Failure, Success, Try}

class MapDBStorage(fileName: String, levels: Int) extends Storage[Tuple2[Int, Long], Array[Byte]] {

  import MapDBStorage._

  private val log = LoggerFactory.getLogger(this.getClass)

  private val dbs =
    (0 to levels) map { n: Int =>
      DBMaker.fileDB(new File(fileName + n + ".mapDB"))
        .fileMmapEnableIfSupported()
        .closeOnJvmShutdown()
        .checksumEnable()
        .make()
    }

  private val maps: Map[Int, HTreeMap[Long, Digest]] = {
    val t = (0 to levels) map { n: Int =>
      val m: HTreeMap[Long, Digest] = dbs(n).hashMapCreate("map_" + n)
        .keySerializer(Serializer.LONG)
        .valueSerializer(Serializer.BYTE_ARRAY)
        .makeOrGet()
      n -> m
    }
    t.toMap
  }

  override def set(key: Key, value: Digest): Unit = {
    val map = maps(key._1.asInstanceOf[Int])
    Try {
      map.put(key._2, value)
    }.recoverWith { case t: Throwable =>
      log.warn("Failed to set key:" + key, t)
      Failure(t)
    }
  }

  override def commit(): Unit = dbs.foreach(_.commit())

  override def close(): Unit = {
    commit()
    dbs.foreach(_.close())
  }

  override def get(key: Key): Option[Digest] = {
    Try {
      maps(key._1).get(key._2)
    } match {
      case Success(v) =>
        Option(v)

      case Failure(e) =>
        if (key._1 == 0) {
          log.debug("Enable to load key for level 0: " + key)
        }
        None
    }
  }

}

object MapDBStorage {
  type Level = Int
  type Position = Long
  type Key = (Level, Position)
  type Value = Digest

}
