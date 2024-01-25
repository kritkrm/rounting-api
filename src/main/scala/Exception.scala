object Exception {

  final case class NoResourceAvailableException()
      extends Exception("No resources available")
}
