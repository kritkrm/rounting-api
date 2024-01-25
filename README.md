## Summary Note

##### This is simple rounting-api with 2 stretegies.
1. Round-robin with circuit breaker
    1. Remove resource from pool when failure attempt threshold is reached.
    2. Resource will be added into pool again after cooldown period. (but still stack old failure attempt).
    3. Reset failure attempt for each resource only when at least 1 succussfully attempt.
2. Weighted round-robin with circuit breaker (random selection)
    1. Remove resource from pool when failure attempt threshold is reached.
    2. Resource will be added into pool again after cooldown period. (but still stack old failure attempt).
    3. Reset failure attempt for each resource only when at least 1 succussfully attempt (including both onSlowSuccess and onSuccess).
    4. Introducing penalty framework to adjust weight of each resource, onSlowSuccess will reduce the weigth and onSuccess will increse the weight of resources.
        1. onSlowSuccess, weight never be adjusted to zero, mean that we never remove slow resource from the pool
        2. onSuccess, weight never be adjusted to be more than maxWeight to prevent bias selection.

## Rounting-api
```
//build docker image
docker build -t rounting-api -f rounting-api.dockerfile .

//run docker
docker run -d -p 8081:8081 rounting-api

//run via sbt
sbt run
```

## Simple-api

```
//build docker image
docker build -t simple-api -f simple-api.dockerfile .

//run docker
docker run -d -p 8091:8081 simple-api

//run via sbt
sbt run
```

## Testing

#### Sampling request script.
```
xargs -I % -P 5 curl --location --request POST '127.0.0.1:8081/api/endpoint' \
--header 'Content-Type: application/json' \
--data-raw '{
    "game": "Mobile Legends",
    "gamerID": "AAA",
    "points": 1
}' < <(printf '%s\n' {1..20})
```

#### Test env from docker-compose.yml
```
- 1 containers of rounting-api
    - Port: 8081
- 3 containers of simple-api
    - Simulated delay in range [DELAY_LOWER_BOUND, DELAY_UPPER_BOUND] milisecond.
    - Port: 8091, 8092, 8093
```

