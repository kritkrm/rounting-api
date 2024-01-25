import Exception.NoResourceAvailableException
import com.typesafe.config.Config

import java.util.List
import java.util.concurrent.ThreadLocalRandom
import scala.util.Try

object WeightedRoundRobinLoadBalancer {

  def apply(
      resources: List[Resource],
      breakerFailureCountThreshold: Long,
      breakerCooldownMs: Long,
      penalty: Long,
      initialWeight: Long,
      maxWeight: Long
  ): WeightedRoundRobinLoadBalancer = {
    if (penalty < 0) {
      throw new RuntimeException("Penalty must be non-negative")
    }
    if (initialWeight <= 0) {
      throw new RuntimeException("Initial weight must be positive")
    }
    if (maxWeight <= 0) {
      throw new RuntimeException("Max weight must be positive")
    }
    if (initialWeight > maxWeight) {
      throw new RuntimeException(
        "Initial weight must be less than or equal max weight"
      )
    }
    if (breakerFailureCountThreshold <= 0) {
      throw new RuntimeException(
        "Breaker failure count threshold must be positive"
      )
    }
    if (breakerCooldownMs < 0) {
      throw new RuntimeException("Breaker cooldown must be non-negative")
    }
    new WeightedRoundRobinLoadBalancer(
      resources = resources,
      breakerFailureCountThreshold = breakerFailureCountThreshold,
      breakerCooldownMs = breakerCooldownMs,
      penalty = penalty,
      initialWeight = initialWeight,
      maxWeight = maxWeight
    )
  }

  def apply(config: Config): WeightedRoundRobinLoadBalancer = {

    val initialWeight = config.getLong("wrr.initial-weight")

    WeightedRoundRobinLoadBalancer(
      resources = ResourceBuilder.build(config, Some(initialWeight)),
      breakerFailureCountThreshold =
        config.getLong("wrr.breaker-failure-count-threshold"),
      breakerCooldownMs = config.getDuration("wrr.breaker-cooldown").toMillis,
      penalty = config.getDuration("wrr.penalty").toMillis,
      initialWeight = initialWeight,
      maxWeight = config.getLong("wrr.max-weight")
    )
  }
}

class WeightedRoundRobinLoadBalancer private (
    resources: List[Resource],
    breakerFailureCountThreshold: Long,
    breakerCooldownMs: Long,
    penalty: Long,
    initialWeight: Long,
    maxWeight: Long
) extends LoadBalancer {

  def getNextInstance(): Either[Exception, Resource] = {

    var totalWeights = 0L
    resources.forEach { resource =>
      reCalculateBreaker(resource)
      totalWeights += resource.getWeight()
      println(
        s"Resource ${resource.getAddress()} has weight ${resource.getWeight()}"
      )
    }
    println(
      s"Total weight: $totalWeights"
    )

    if (totalWeights == 0)
      return Left(new NoResourceAvailableException)

    var weightsToNextResource = {
      Try {
        ThreadLocalRandom.current().nextLong(1, totalWeights + 1)
      } match {
        case scala.util.Success(value) => value
        case scala.util.Failure(exception) =>
          return Left(new RuntimeException(exception.getCause))
      }
    }

    if (resources.size() == 0)
      return Left(new NoResourceAvailableException)

    var biggestResource = resources.get(0)
    for (i <- 0 until resources.size()) {
      val candidate = resources.get(i)
      weightsToNextResource -= candidate.getWeight()
      if (weightsToNextResource <= 0) {
        return Right(candidate)
      }
      if (biggestResource.getWeight() < candidate.getWeight())
        biggestResource = candidate
    }

    // this is fallback if weight was decreased after totalWeights is calculated
    if (biggestResource.isAvaiable())
      Right(biggestResource)
    else
      Left(new NoResourceAvailableException)
  }

  def onSuccess(resource: Resource): Unit = {
    resource.disableBreaker()
    resource.resetFailureCounter()
    resource.increaseWeight(penalty, maxWeight)
  }

  def onSlowSuccess(resource: Resource): Unit = {
    resource.disableBreaker()
    resource.resetFailureCounter()
    resource.decreaseWeight(penalty, 1)
  }

  def onFailure(resource: Resource): Unit = {
    resource.updateLastFailureTimestamp(System.currentTimeMillis())
    resource.increaseFailureCounter()
    if (resource.getFailureCounter() >= breakerFailureCountThreshold) {
      resource.enableBreaker()
      resource.setWeight(0L)
    }
  }

  private def reCalculateBreaker(resource: Resource): Unit = {
    if (
      !resource.isAvaiable() && System.currentTimeMillis() - resource
        .getLastFailureTimestamp() >= breakerCooldownMs
    ) {
      resource.disableBreaker()
      resource.setWeight(initialWeight)
    }
  }
}
