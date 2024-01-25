import Exception.NoResourceAvailableException
import com.typesafe.config.Config

import java.util.List
import java.util.concurrent.atomic.AtomicInteger

object RoundRobinLoadBalancer {

  def apply(
      resources: List[Resource],
      breakerFailureCountThreshold: Long,
      breakerCooldownMs: Long
  ): RoundRobinLoadBalancer = {
    if (breakerFailureCountThreshold <= 0) {
      throw new RuntimeException(
        "Breaker failure count threshold must be positive"
      )
    }

    if (breakerCooldownMs < 0) {
      throw new RuntimeException("Breaker cooldown must be non-negative")
    }

    new RoundRobinLoadBalancer(
      resources = resources,
      breakerFailureCountThreshold = breakerFailureCountThreshold,
      breakerCooldownMs = breakerCooldownMs
    )
  }

  def apply(
      config: Config
  ): RoundRobinLoadBalancer = {
    RoundRobinLoadBalancer(
      resources = ResourceBuilder.build(config),
      breakerFailureCountThreshold =
        config.getLong("rr.breaker-failure-count-threshold"),
      breakerCooldownMs = config.getDuration("rr.breaker-cooldown").toMillis
    )
  }
}

class RoundRobinLoadBalancer private (
    resources: List[Resource],
    breakerFailureCountThreshold: Long,
    breakerCooldownMs: Long
) extends LoadBalancer {
  private val currentCounter = new AtomicInteger()

  def onSuccess(resource: Resource): Unit = {
    resource.resetFailureCounter()
    resource.disableBreaker()
  }

  def onSlowSuccess(resource: Resource): Unit = onSuccess(resource)

  def onFailure(resource: Resource): Unit = {
    resource.updateLastFailureTimestamp(System.currentTimeMillis())
    resource.increaseFailureCounter()
    if (resource.getFailureCounter() >= breakerFailureCountThreshold) {
      resource.enableBreaker()
    }
  }

  private def reCalculateBreaker(
      resource: Resource
  ): Unit = {
    if (
      !resource.isAvaiable() && System.currentTimeMillis() - resource
        .getLastFailureTimestamp() >= breakerCooldownMs
    ) {
      resource.disableBreaker()
    }
  }

  def getNextInstance(): Either[Exception, Resource] = {

    var attemptLeft = resources.size()
    while (attemptLeft > 0) {
      attemptLeft -= 1

      val candidate =
        resources.get(currentCounter.getAndIncrement() % resources.size)

      reCalculateBreaker(candidate)

      if (candidate.isAvaiable())
        return Right(candidate)
    }

    Left(new NoResourceAvailableException)
  }
}
