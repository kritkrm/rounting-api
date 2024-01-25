import com.typesafe.config.Config

import java.util.List
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong, LongAdder}
import scala.jdk.CollectionConverters._

class Resource private (address: String, initalWeight: Option[Long]) {

  override def toString(): String = s"Resource($address, $weight)"

  private val weight: AtomicLong = new AtomicLong(initalWeight.getOrElse(0))
  private val failureCounter: LongAdder = new LongAdder()
  private val lastFailureTimestamp: AtomicLong = new AtomicLong()
  private val isBreakerOpened: AtomicBoolean = new AtomicBoolean(false)

  def getAddress(): String = address

  def resetFailureCounter(): Unit = failureCounter.reset()
  def decreaseWeight(delta: Long, minWeight: Long): Unit = {
    val currentWeight = weight.get()
    weight.set(Math.max(currentWeight - delta, minWeight))
  }

  def getFailureCounter(): Long = failureCounter.sum()
  def increaseFailureCounter(): Unit = failureCounter.increment()

  def getWeight(): Long = weight.get()
  def setWeight(value: Long): Unit = weight.set(value)
  def increaseWeight(delta: Long, maxWeight: Long): Unit = {
    val currentWeight = weight.get()
    weight.set(Math.min(currentWeight + delta, maxWeight))
  }

  def isAvaiable(): Boolean = !isBreakerOpened.get()
  def enableBreaker(): Unit = isBreakerOpened.set(true)
  def disableBreaker(): Unit = isBreakerOpened.set(false)

  def getLastFailureTimestamp(): Long = lastFailureTimestamp.get()
  def updateLastFailureTimestamp(timeStamp: Long): Unit =
    lastFailureTimestamp.set(timeStamp)

}

object Resource {
  def apply(address: String, initialWeight: Option[Long] = None): Resource = {
    if (initialWeight.exists(_ < 0))
      throw new RuntimeException("Initial weight must be positive")
    new Resource(address, initialWeight)
  }
}
object ResourceBuilder {

  def build(
      config: Config,
      initialWeight: Option[Long] = None
  ): List[Resource] = {
    config
      .getStringList("instances")
      .asScala
      .toSeq
      .map(Resource(_, initialWeight))
      .asJava
  }
}
