import Exception.NoResourceAvailableException
import org.scalatest.AppendedClues.convertToClueful
import org.scalatest.BeforeAndAfterEach
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.concurrent.{CountDownLatch, Executors}
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.Duration
import scala.jdk.CollectionConverters._

class WeightedRoundRobinLoadBalancerSpec
    extends AnyWordSpec
    with Matchers
    with BeforeAndAfterEach {

  val breakerFailureCountThreshold = 2
  val breakerCooldownMs = Duration("1 d").toMillis
  val penalty = 1
  val initialWeight = 2
  val maxWeight = 10

  val resource1 = Resource("address-01", Some(initialWeight))
  val resource2 = Resource("address-02", Some(initialWeight))
  val resource3 = Resource("address-03", Some(initialWeight))
  val resources = Seq(resource1, resource2, resource3)

  override def beforeEach() = {
    resources.foreach(resource => {
      resource.resetFailureCounter()
      resource.disableBreaker()
      resource.updateLastFailureTimestamp(0)
      resource.setWeight(initialWeight)
    })
  }
  "WeightedRoundRobinLoadBalancer" should {

    val loadBalancer = WeightedRoundRobinLoadBalancer(
      resources = resources.asJava,
      breakerFailureCountThreshold = breakerFailureCountThreshold,
      breakerCooldownMs = breakerCooldownMs,
      penalty = penalty,
      initialWeight = initialWeight,
      maxWeight = maxWeight
    )

    "Apply distributed based on weighted round-robin fashion correctly - Uniform" in {
      val total = 1000000
      val totalWeight = resources.map(_.getWeight()).sum

      val counter: TrieMap[Resource, Int] = TrieMap.empty
      resources.foreach(resource => counter.put(resource, 0))

      for (_ <- 1 to total) {
        loadBalancer.getNextInstance() match {
          case Right(resource) => counter(resource) += 1
        }
      }

      resources.foreach(resource => {
        val expectedCount = total * resource.getWeight() / totalWeight
        val error = 0.03d
        val lowerBound = expectedCount * (1 - error)
        val upperBound = expectedCount * (1 + error)
        val count = counter(resource)
        (lowerBound < count && count < upperBound) shouldBe true withClue s"count should be in [$lowerBound, $upperBound], count: $count"
        println(
          s"$resource should be in [$lowerBound, $upperBound], count: $count"
        )
      })

    }
    "Apply distributed based on weighted round-robin fashion correctly - Non-Uniform" in {
      val total = 1000000

      resource1.setWeight(25)
      resource2.setWeight(25)
      resource3.setWeight(50)

      val totalWeight = resources.map(_.getWeight()).sum

      val counter: TrieMap[Resource, Int] = TrieMap.empty
      resources.foreach(resource => counter.put(resource, 0))

      for (_ <- 1 to total) {
        loadBalancer.getNextInstance() match {
          case Right(resource) => counter(resource) += 1
        }
      }

      resources.foreach(resource => {
        val expectedCount = total * resource.getWeight() / totalWeight
        val error = 0.03d
        val lowerBound = expectedCount * (1 - error)
        val upperBound = expectedCount * (1 + error)
        val count = counter(resource)
        (lowerBound < count && count < upperBound) shouldBe true withClue s"count should be in [$lowerBound, $upperBound], count: $count"
        println(
          s"$resource should be in [$lowerBound, $upperBound], count: $count"
        )
      })

    }
    "Concurency Check - Apply distributed based on weighted round-robin fashion correctly - Non-Uniform" in {
      val total = 1000000

      resource1.setWeight(25)
      resource2.setWeight(25)
      resource3.setWeight(50)

      val totalWeight = resources.map(_.getWeight()).sum

      val counter: TrieMap[Resource, Int] = TrieMap.empty
      resources.foreach(resource => counter.put(resource, 0))

      val threadCount = 1
      val threadPool = Executors.newFixedThreadPool(threadCount)

      val latch = new CountDownLatch(total)

      for (_ <- 1 to total) {
        threadPool.execute(new Runnable {
          override def run(): Unit = {
            loadBalancer.getNextInstance() match {
              case Right(resource) => counter(resource) += 1
            }
            latch.countDown()
          }
        })
      }
      latch.await()

      resources.foreach(resource => {
        val expectedCount = total * resource.getWeight() / totalWeight
        val error = 0.03d
        val lowerBound = expectedCount * (1 - error)
        val upperBound = expectedCount * (1 + error)
        val count = counter(resource)
        (lowerBound < count && count < upperBound) shouldBe true withClue s"count should be in [$lowerBound, $upperBound], count: $count"
        println(
          s"$resource should be in [$lowerBound, $upperBound], count: $count"
        )
      })

    }
    "Apply distributed based on weighted round-robin fashion correctly - Zero weight" in {
      val total = 1000000

      resource1.setWeight(0)
      resource2.setWeight(25)
      resource3.setWeight(50)

      val totalWeight = resources.map(_.getWeight()).sum

      val counter: TrieMap[Resource, Int] = TrieMap.empty
      resources.foreach(resource => counter.put(resource, 0))

      for (_ <- 1 to total) {
        loadBalancer.getNextInstance() match {
          case Right(resource) => counter(resource) += 1
        }
      }

      resources.foreach(resource => {
        val expectedCount = total * resource.getWeight() / totalWeight
        val error = 0.03d
        val lowerBound = expectedCount * (1 - error)
        val upperBound = expectedCount * (1 + error)
        val count = counter(resource)
        (lowerBound <= count && count <= upperBound) shouldBe true withClue s"count should be in [$lowerBound, $upperBound], count: $count"
        println(
          s"$resource should be in [$lowerBound, $upperBound], count: $count"
        )
      })

    }

    "Open breaker with weight 0 if failure of resource reach the limit" in {

      resource2.getWeight() shouldBe initialWeight
      resource2.isAvaiable() shouldBe true

      for (_ <- 1 to breakerFailureCountThreshold) {
        loadBalancer.onFailure(resource2)
      }

      resource2.getWeight() shouldBe 0
      resource2.isAvaiable() shouldBe false

    }

    "OnSuccess - Close breaker with initialWeight and reset failure count if at least one request is processed succesfully" in {

      resource2.getWeight() shouldBe initialWeight
      resource2.isAvaiable() shouldBe true

      for (_ <- 1 to breakerFailureCountThreshold) {
        loadBalancer.onFailure(resource2)
      }

      resource2.getWeight() shouldBe 0
      resource2.isAvaiable() shouldBe false

      loadBalancer.onSuccess(resource2)

      resource2.getWeight() shouldBe penalty
      resource2.isAvaiable() shouldBe true

    }

    "OnSlowSuccess - Close breaker with initialWeight and reset failure count if at least one request is processed succesfully" in {

      resource2.getWeight() shouldBe initialWeight
      resource2.isAvaiable() shouldBe true

      for (_ <- 1 to breakerFailureCountThreshold) {
        loadBalancer.onFailure(resource2)
      }

      resource2.getWeight() shouldBe 0
      resource2.isAvaiable() shouldBe false

      loadBalancer.onSlowSuccess(resource2)

      resource2.getWeight() shouldBe penalty
      resource2.isAvaiable() shouldBe true

    }

    "OnSlowSuccess - Decrease weight by penalty with bound" in {

      resource2.getWeight() shouldBe initialWeight
      resource2.isAvaiable() shouldBe true

      loadBalancer.onSlowSuccess(resource2)

      resource2.getWeight() shouldBe initialWeight - penalty
      resource2.isAvaiable() shouldBe true

      for (_ <- 1 to (initialWeight / penalty) + 10) {
        loadBalancer.onSlowSuccess(resource2)
      }

      resource2.getWeight() shouldBe 1
      resource2.isAvaiable() shouldBe true

    }
    "Return No availiable exception if all resource broken" in {
      resources.foreach(resource => {
        resource.enableBreaker()
        resource.setWeight(0)
        resource.updateLastFailureTimestamp(System.currentTimeMillis())
      })

      loadBalancer.getNextInstance() shouldBe Left(
        new NoResourceAvailableException
      )

    }

  }

}
