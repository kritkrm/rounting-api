trait LoadBalancer {
  def getNextInstance(): Either[Exception, Resource]
  def onSuccess(resource: Resource): Unit
  def onSlowSuccess(resource: Resource): Unit
  def onFailure(resource: Resource): Unit
}
