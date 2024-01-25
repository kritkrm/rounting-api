import Exception.NoResourceAvailableException
import org.scalatest.BeforeAndAfterEach
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.duration.Duration
import scala.jdk.CollectionConverters._

class RoundRobinLoadBalancerSpec
    extends AnyWordSpec
    with Matchers
    with BeforeAndAfterEach {

  val breakerFailureCountThreshold = 2
  val breakerCooldownMs = Duration("1 d").toMillis

  val resource1 = Resource("address-01")
  val resource2 = Resource("address-02")
  val resource3 = Resource("address-03")
  val resources = Seq(resource1, resource2, resource3)

  override def beforeEach() = {
    resources.foreach(resource => {
      resource.resetFailureCounter()
      resource.disableBreaker()
      resource.updateLastFailureTimestamp(0)
    })
  }
  "RoundRobinLoadBalancer" should {

    val loadBalancer = RoundRobinLoadBalancer(
      resources = Seq(resource1, resource2, resource3).asJava,
      breakerFailureCountThreshold = breakerFailureCountThreshold,
      breakerCooldownMs = breakerCooldownMs
    )

    "Apply round-robin fashion correctly" in {
      loadBalancer.getNextInstance() shouldBe Right(resource1)
      loadBalancer.getNextInstance() shouldBe Right(resource2)
      loadBalancer.getNextInstance() shouldBe Right(resource3)

      loadBalancer.getNextInstance() shouldBe Right(resource1)
      loadBalancer.getNextInstance() shouldBe Right(resource2)
      loadBalancer.getNextInstance() shouldBe Right(resource3)
    }

    "Open breaker if failure of resource reach the limit" in {
      for (_ <- 1 to breakerFailureCountThreshold) {
        loadBalancer.onFailure(resource2)
      }
      loadBalancer.getNextInstance() shouldBe Right(resource1)
      loadBalancer.getNextInstance() shouldBe Right(resource3)
      loadBalancer.getNextInstance() shouldBe Right(resource1)
      loadBalancer.getNextInstance() shouldBe Right(resource3)
    }

    "Close breaker after cooldown is passed" in {
      for (_ <- 1 to breakerFailureCountThreshold) {
        loadBalancer.onFailure(resource2)
      }
      loadBalancer.getNextInstance() shouldBe Right(resource1)
      loadBalancer.getNextInstance() shouldBe Right(resource3)
      loadBalancer.getNextInstance() shouldBe Right(resource1)
      loadBalancer.getNextInstance() shouldBe Right(resource3)

      resource2.updateLastFailureTimestamp(
        System.currentTimeMillis() - breakerCooldownMs
      )
      loadBalancer.getNextInstance() shouldBe Right(resource1)
      loadBalancer.getNextInstance() shouldBe Right(resource2)
      loadBalancer.getNextInstance() shouldBe Right(resource3)

      loadBalancer.getNextInstance() shouldBe Right(resource1)
      loadBalancer.getNextInstance() shouldBe Right(resource2)
      loadBalancer.getNextInstance() shouldBe Right(resource3)
    }

    "Close breaker and reset failure count if at least one request is processed succesfully" in {
      for (_ <- 1 to breakerFailureCountThreshold) {
        loadBalancer.onFailure(resource2)
      }
      loadBalancer.getNextInstance() shouldBe Right(resource1)
      loadBalancer.getNextInstance() shouldBe Right(resource3)
      loadBalancer.getNextInstance() shouldBe Right(resource1)
      loadBalancer.getNextInstance() shouldBe Right(resource3)

      resource2.getFailureCounter() shouldBe 2

      loadBalancer.onSuccess(resource2)

      resource2.getFailureCounter() shouldBe 0

      loadBalancer.getNextInstance() shouldBe Right(resource1)
      loadBalancer.getNextInstance() shouldBe Right(resource2)
      loadBalancer.getNextInstance() shouldBe Right(resource3)

      loadBalancer.getNextInstance() shouldBe Right(resource1)
      loadBalancer.getNextInstance() shouldBe Right(resource2)
      loadBalancer.getNextInstance() shouldBe Right(resource3)
    }

    "Return No availiable exception if all resource broken" in {
      resources.foreach(resource => {
        resource.enableBreaker()
        resource.updateLastFailureTimestamp(System.currentTimeMillis())
      })

      loadBalancer.getNextInstance() shouldBe Left(
        new NoResourceAvailableException
      )

    }

  }

}
